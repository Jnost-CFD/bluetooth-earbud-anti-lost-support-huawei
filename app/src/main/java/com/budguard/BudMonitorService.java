package com.budguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 断连提醒：前台服务持续观察耳机与手机之间的蓝牙链路状态。
 * 链路断开（耳机没电、被拿走、走出范围、放回舱内关机）→ 延迟数秒确认后弹提醒；
 * 未重新连接则按间隔循环提醒，直到用户手动关闭或耳机重新连上。
 */
public class BudMonitorService extends Service {

    public static final String ACTION_START = "com.budguard.action.START";
    public static final String ACTION_STOP = "com.budguard.action.STOP";
    /** 用户在确认项里选择了「在仓内」。 */
    public static final String ACTION_CONFIRM_INCASE = "com.budguard.action.INCASE";
    /** 用户在确认项里选择了「不在仓」。 */
    public static final String ACTION_CONFIRM_NOTINCASE = "com.budguard.action.NOTINCASE";
    /** 回到身边后：清除遗落记录 / 保留。 */
    public static final String ACTION_CLEAR_HISTORY = "com.budguard.action.CLEAR_HISTORY";
    public static final String ACTION_KEEP_HISTORY = "com.budguard.action.KEEP_HISTORY";

    /** 进程内标记：服务是否已经在跑（供界面判断是否需要重启守护）。 */
    public static volatile boolean RUNNING = false;

    private static final String CH_ONGOING = "budguard_ongoing";
    private static final String CH_ALERT = "budguard_alert";
    private static final int ID_ONGOING = 0x1001;
    private static final int ID_ALERT = 0x1002;
    private static final long POLL_MS = 10000L;
    private static final long REPEAT_MS = 30000L;

    private Store store;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;

    private String addr;
    private String devName;
    private Boolean lastConnected = null;
    private boolean pendingAlarm = false;
    private boolean guardStarted = false;

    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            checkState();
            h.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable alarm = new Runnable() {
        @Override
        public void run() {
            pendingAlarm = false;
            if (isConnectedNow()) return;
            alert();
        }
    };

    // ---------------- 收纳态（充电仓）遗落提醒 ----------------
    // 充电仓不参与 A2DP 连接，只能靠它自己的 BLE 广播判断「在不在身边」。
    // 扫描做占空比（扫 12s / 停 40s）省电；只有手机确实移动过才允许报警，避免坐着不动误报。

    private static final int ID_PRESENCE = 0x1003;
    private static final int ID_CONFIRM = 0x1004;
    private static final int ID_CLEAR = 0x1005;
    /** 扫描 15 秒 / 停 20 秒。 */
    private static final long SCAN_ON_MS = 15_000L;
    private static final long SCAN_OFF_MS = 20_000L;
    /** 判定周期：8 秒。 */
    private static final long PRESENCE_TICK_MS = 8_000L;
    /** 超出距离需要持续多久才报：6s（调小 = 更敏感）。 */
    private static final long FAR_SUSTAIN_MS = 6_000L;
    /** 样本新鲜度：距离判定只认最近 10 秒内的样本，停顿期不拿旧数据下结论。 */
    private static final long SAMPLE_FRESH_MS = 10_000L;
    /** 提醒冷却：同一场景下最多 2 分钟提醒一次。 */
    private static final long ALERT_COOLDOWN_MS = 120_000L;
    /** 确认超时：90 秒没响应，按「不在仓」处理并照常报警。 */
    private static final long CONFIRM_TIMEOUT_MS = 90_000L;

    private BluetoothLeScanner leScanner;
    private MotionDetector motion;
    private Profile profile = new Profile();
    /** 每个部件（左耳/右耳/仓）最后一次被听到的时间。 */
    private final java.util.Map<String, Long> seenAt = new java.util.HashMap<>();
    /** 每个部件自己的信号模型（距离估算要分开算）。 */
    private final java.util.Map<String, SignalModel> models = new java.util.HashMap<>();
    /** 每个部件「开始超出距离」的时刻，用于要求持续一段时间。 */
    private final java.util.Map<String, Long> farSince = new java.util.HashMap<>();
    /** 当前已提醒的部件组合，组合变化时允许再次提醒。 */
    private String alertedKey;
    /** 遗落状态：用来驱动常驻通知的「在附近 / 遗落」。 */
    private boolean leftBehind;
    /** 本轮是否已经问过「耳机是否在仓内」（回答或超时后不再重复问）。 */
    private boolean askedThisEpisode;
    /** 上一次遗落提醒的时间，用于 2 分钟冷却。 */
    private long lastAlertAt;
    /** 最近一次测得的距离（米），-1 表示没有有效样本。 */
    private float liveMeters = -1f;
    private long lastDistWrite;
    /** 定位采样与「最后一次贴近（≤2m）时」的定位，用于遗落落点拟合。 */
    private LocTracker locTracker;
    private Loc lastNear;
    /** 弹出「是否在仓内」询问的时刻。 */
    private long confirmAskedAt;
    /** 非 0 时，记录遗落位置要倒推到该时刻（用答问之前的定位）。 */
    private long rewindTo;
    /** 当前缺失部件的文案（"左耳+右耳遗落" 等），通知栏与提醒共用。 */
    private String lastMissingText = "";
    private boolean scanning;

    private final ScanCallback beaconCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            if (profile.isEmpty()) return;
            Beacon b = Beacon.from(result);
            long now = System.currentTimeMillis();
            for (Profile.Comp c : profile.comps) {
                if (!c.matches(b.sigs)) continue;
                seenAt.put(c.role, now);
                farSince.remove(c.role);
                SignalModel m = models.get(c.role);
                if (m == null) {
                    m = new SignalModel();
                    models.put(c.role, m);
                }
                m.add(b.rssi);
                liveMeters = (float) m.meters(profile.txPower);
                long t2 = System.currentTimeMillis();
                if (t2 - lastDistWrite > 2000) {          // 限频写盘
                    lastDistWrite = t2;
                    store.setLastDistance(liveMeters, t2);
                }
                // 贴近时刻（≤2 米）记下当时的定位：这就是遗落落点最可信的估计
                if (liveMeters <= LeaveEstimator.NEAR_METERS && locTracker != null) {
                    Loc l = locTracker.last();
                    if (l != null && l.valid()) lastNear = l;
                }
                // 左/右耳重新出现 = 被取出用过 → 「在仓内」的确认作废，下一轮静默要重新问
                if (!Profile.ROLE_CASE.equals(c.role)) {
                    askedThisEpisode = false;
                    if (store.inCaseConfirmed()) {
                        store.setInCaseConfirmed(false);
                        store.clearConfirm();
                        cancelConfirmNotification();
                        updateOngoing(lastConnected);
                    }
                }
                onBeaconSeen();
                return;
            }
        }
    };

    /**
     * 持续低功耗扫描（不再「扫 12 秒停 40 秒」）。
     * 8 秒一次的巡检只负责评估在场，扫描本身一直开着，这样：
     *  - 反应时间 = 一个广播周期，而不是等下一次开扫；
     *  - 不会因为频繁 start/stop 触发系统的扫描启动限流。
     */
    private void startScan() {
        if (scanning) return;
        try {
            if (adapter == null || !adapter.isEnabled()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (leScanner == null) leScanner = adapter.getBluetoothLeScanner();
            if (leScanner == null) return;
            leScanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build(), beaconCallback);
            scanning = true;
        } catch (Exception ignored) {
        }
    }

    private final Runnable scanKeepAlive = new Runnable() {
        @Override
        public void run() {
            if (profile.isEmpty()) return;
            if (!scanning) startScan();          // 蓝牙关闭/权限变化后自愈
            h.postDelayed(this, PRESENCE_TICK_MS);
        }
    };

    // ---- 15 秒扫描 / 20 秒停顿的占空比 ----
    private final Runnable scanOn = new Runnable() {
        @Override
        public void run() {
            if (profile.isEmpty() || !store.presence()) return;
            startScan();
            h.postDelayed(scanOff, SCAN_ON_MS);
        }
    };

    private final Runnable scanOff = new Runnable() {
        @Override
        public void run() {
            try {
                if (leScanner != null && scanning) leScanner.stopScan(beaconCallback);
            } catch (Exception ignored) {
            }
            scanning = false;
            if (profile.isEmpty() || !store.presence()) return;
            h.postDelayed(scanOn, SCAN_OFF_MS);
        }
    };

    private final Runnable presenceTick = new Runnable() {
        @Override
        public void run() {
            checkAbsence();
            // 每一轮都刷新常驻通知：回到范围后状态栏能立刻从「遗落」变回「在附近」
            updateOngoing(lastConnected);
            h.postDelayed(this, PRESENCE_TICK_MS);
        }
    };

    private void startPresence() {
        stopPresence();
        profile = store.profile();
        if (profile.isEmpty() || !store.presence()) return;
        if (motion == null) motion = new MotionDetector(this);
        motion.start();
        if (locTracker == null) locTracker = new LocTracker(this);
        locTracker.start();
        long now = System.currentTimeMillis();
        seenAt.clear();
        models.clear();
        farSince.clear();
        alertedKey = null;
        askedThisEpisode = false;
        for (Profile.Comp c : profile.comps) seenAt.put(c.role, now);
        h.post(scanOn);                                 // 扫 15 秒 / 停 20 秒
        h.postDelayed(presenceTick, PRESENCE_TICK_MS);
    }

    private void stopPresence() {
        h.removeCallbacks(scanOn);
        h.removeCallbacks(scanOff);
        h.removeCallbacks(scanKeepAlive);
        h.removeCallbacks(presenceTick);
        try {
            if (leScanner != null && scanning) leScanner.stopScan(beaconCallback);
        } catch (Exception ignored) {
        }
        scanning = false;
        if (motion != null) motion.stop();
        if (locTracker != null) locTracker.stop();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_PRESENCE);
    }

    private void onBeaconSeen() {
        if (alertedKey == null && !leftBehind) return;
        long now = System.currentTimeMillis();
        long absentMs = store.absenceSec() * 1000L;
        for (Profile.Comp c : profile.comps) {
            Long t = seenAt.get(c.role);
            if (t == null || now - t > absentMs) return;   // 还有部件没回来，保持提醒
        }
        alertedKey = null;
        if (leftBehind) {
            leftBehind = false;
            updateOngoing(lastConnected);
            // 回到身边：如果有遗落记录，问一次要不要清掉
            if (!store.events().isEmpty()) {
                store.setAskClearHistory(true);
                showClearPrompt();
            }
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_PRESENCE);
    }

    /** 通知/界面用：当前距离文案。超过 20 秒没有新样本就认为信号已断开。 */
    private String distanceText() {
        long seen = store.lastSeenAt();
        if (liveMeters < 0 || seen == 0 || System.currentTimeMillis() - seen > 20_000L) {
            return "信号已断开";
        }
        return liveMeters < 10
                ? String.format(java.util.Locale.US, "约 %.1f 米", liveMeters)
                : "约 " + Math.round(liveMeters) + " 米";
    }

    /**
     * 按「部件」逐个判定：左耳 / 右耳 / 充电仓各自一个地址、各自一个信号模型。
     *  - gone：静默超过 absenceSec
     *  - far ：还能收到，但按模型已明显超出设定距离，且持续 FAR_SUSTAIN_MS
     * 任一部件判定成立就纳入缺失集合；集合变化才重新提醒（同一组合只响一次）。
     */
    /** 越近的档位留的噪声裕量越小：<1m 用 0.2σ，2m 用 0.4σ，5m 用 0.5σ。 */
    private static double marginFor(int meters) {
        if (meters <= 1) return 0.2;
        if (meters <= 2) return 0.4;
        return 0.5;
    }

    private void checkAbsence() {
        if (profile.isEmpty()) return;
        long now = System.currentTimeMillis();
        long absentMs = store.absenceSec() * 1000L;
        int meters = store.alarmMeters();
        boolean tooFar = false;
        java.util.List<String> missing = new java.util.ArrayList<>();

        for (Profile.Comp c : profile.comps) {
            Long t = seenAt.get(c.role);
            long silent = t == null ? Long.MAX_VALUE : now - t;
            boolean gone = silent > absentMs;
            SignalModel m = models.get(c.role);
            boolean far = false;
            // 样本新鲜度门槛：只在「扫描进行中」且「最近 10 秒内有新样本」时才判距离。
            // 这样 20 秒停顿期不会拿旧 RSSI 下结论，避免基于过期数据误报。
            boolean fresh = scanning && m != null && m.hasData()
                    && (now - m.lastSampleAt()) <= SAMPLE_FRESH_MS;

            if (!gone && meters > 0 && fresh
                    && m.beyond(meters, profile.txPower, marginFor(meters))) {
                Long since = farSince.get(c.role);
                if (since == null) {
                    farSince.put(c.role, now);
                } else if (now - since >= FAR_SUSTAIN_MS) {
                    far = true;
                    tooFar = true;
                }
            } else if (farSince.containsKey(c.role)) {
                farSince.remove(c.role);
            }

            if (gone || far) missing.add(c.role);
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // ---- 「耳机是否在充电仓内」的确认逻辑 ----
        boolean inCase = store.inCaseConfirmed();
        boolean casePresent = !missing.contains(Profile.ROLE_CASE);
        boolean budsMissing = missing.contains(Profile.ROLE_L) && missing.contains(Profile.ROLE_R);

        // 已确认在仓内：只关心充电仓本身，不再报左右耳
        if (inCase) {
            java.util.List<String> onlyCase = new java.util.ArrayList<>();
            if (!casePresent) onlyCase.add(Profile.ROLE_CASE);
            missing = onlyCase;
        }

        // 仓在、左右耳都静默：本轮先问一次「是否在仓内」，问过就不再重复问
        if (!inCase && budsMissing && casePresent) {
            if (!askedThisEpisode) {
                askedThisEpisode = true;
                confirmAskedAt = now;
                store.startConfirm(now + CONFIRM_TIMEOUT_MS);
                showConfirm();
                updateOngoing(lastConnected);
                return;
            }
            if (store.confirmPending()) {
                if (now > store.confirmDeadline()) {
                    // 90 秒没响应 → 按「不在仓」处理，落到下面的正常报警
                    store.clearConfirm();
                    cancelConfirmNotification();
                } else {
                    return;                     // 还在等用户回答
                }
            }
            // 已回答「不在仓」（或超时）→ 继续走正常报警流程
        }

        // 询问条件不再成立（例如仓也没了 / 左右耳又出现了）→ 收起确认项
        if (store.confirmPending()) {
            store.clearConfirm();
            cancelConfirmNotification();
            updateOngoing(lastConnected);
        }

        if (missing.isEmpty()) {
            askedThisEpisode = false;              // 本轮结束，下次静默重新询问
            lastAlertAt = 0;                       // 回到身边即重置冷却，下次遗落立刻提醒
            if (alertedKey != null) {
                alertedKey = null;
                if (nm != null) nm.cancel(ID_PRESENCE);
                if (leftBehind) {
                    leftBehind = false;
                    updateOngoing(lastConnected);
                }
            }
            return;
        }
        if (motion == null || !motion.movedRecently()) return;

        StringBuilder key = new StringBuilder();
        for (String s : missing) key.append(s).append(',');
        if (key.toString().equals(alertedKey)) return;
        alertedKey = key.toString();

        lastMissingText = Profile.missingText(
                missing.contains(Profile.ROLE_L),
                missing.contains(Profile.ROLE_R),
                missing.contains(Profile.ROLE_CASE));

        if (!leftBehind) {
            leftBehind = true;
        }
        updateOngoing(lastConnected);   // 状态栏与提醒文案保持一致

        // 冷却期：状态照常更新，但不重复打扰
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return;
        lastAlertAt = now;
        recordLeave(missing);
        alert(missing, tooFar, meters);
    }

    // ---------------- 遗落记录（时间 + 距离 + 位置） ----------------

    private void recordLeave(java.util.List<String> missing) {
        try {
            LeaveEvent e = new LeaveEvent();
            long rewind = rewindTo;
            e.at = rewind > 0 ? rewind : System.currentTimeMillis();
            e.missingText = Profile.missingText(
                    missing.contains(Profile.ROLE_L),
                    missing.contains(Profile.ROLE_R),
                    missing.contains(Profile.ROLE_CASE));

            long absentMs = store.absenceSec() * 1000L;
            Loc partLoc = null;

            for (String role : missing) {
                LeaveEvent.Part p = new LeaveEvent.Part();
                p.role = role;
                Long t = seenAt.get(role);
                p.lastHeardAt = t == null ? 0 : t;

                SignalModel m = models.get(role);
                boolean stillHeard = p.lastHeardAt > 0
                        && (System.currentTimeMillis() - p.lastHeardAt) <= absentMs;
                p.meters = (stillHeard && m != null && m.hasData())
                        ? (float) m.meters(profile.txPower) : -1f;

                if (p.lastHeardAt > 0 && locTracker != null) {
                    p.loc = locTracker.lastBefore(p.lastHeardAt);
                    if (p.loc != null) partLoc = p.loc;
                }
                e.parts.add(p);
            }

            Loc near = lastNear != null ? lastNear : partLoc;
            Loc detected = locTracker != null ? locTracker.last() : null;
            if (rewind > 0 && locTracker != null) {
                Loc past = locTracker.lastBefore(rewind);
                if (past != null) detected = past;
            }
            rewindTo = 0;

            e.detected = detected;
            e.estimate = LeaveEstimator.fuse(near, detected);
            e.confidence = LeaveEstimator.confidence(e.estimate, near != null);
            store.addEvent(e);
            if (e.estimate != null) geocodeAsync(e);
        } catch (Exception ignored) {
        }
    }

    /** 反向地理编码放后台线程，解析完回写存储（界面上地址会从空变成中文）。 */
    private void geocodeAsync(final LeaveEvent e) {
        final Loc l = e.best();
        if (l == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String addr = Geocode.reverse(BudMonitorService.this, l);
                    if (addr != null && !addr.isEmpty()) {
                        e.address = addr;
                        store.updateEventAddress(e.at, addr);
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ---------------- 回到身边：询问是否清除遗落记录 ----------------

    private void showClearPrompt() {
        Intent clr = new Intent(this, ConfirmReceiver.class).setAction(ACTION_CLEAR_HISTORY);
        PendingIntent piClear = PendingIntent.getBroadcast(this, 20, clr, pendingFlags());
        Intent keep = new Intent(this, ConfirmReceiver.class).setAction(ACTION_KEEP_HISTORY);
        PendingIntent piKeep = PendingIntent.getBroadcast(this, 21, keep, pendingFlags());
        PendingIntent open = PendingIntent.getActivity(this, 22,
                new Intent(this, MainActivity.class), pendingFlags());

        int n = store.events().size();
        Notification.Builder b = new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle("耳机已回到身边")
                .setContentText("要清除这 " + n + " 条遗落记录吗？")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "清除", piClear).build())
                .addAction(new Notification.Action.Builder(null, "保留", piKeep).build());

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(ID_CLEAR, b.build());
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelClearPrompt() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_CLEAR);
    }

    // ---------------- 仓内确认：通知 + 主页 ----------

    private void showConfirm() {
        Intent in = new Intent(this, ConfirmReceiver.class).setAction(ACTION_CONFIRM_INCASE);
        PendingIntent piIn = PendingIntent.getBroadcast(this, 10, in, pendingFlags());
        Intent out = new Intent(this, ConfirmReceiver.class).setAction(ACTION_CONFIRM_NOTINCASE);
        PendingIntent piOut = PendingIntent.getBroadcast(this, 11, out, pendingFlags());
        PendingIntent open = PendingIntent.getActivity(this, 12,
                new Intent(this, MainActivity.class), pendingFlags());

        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle("耳机是否在充电仓内？")
                .setContentText("充电仓在附近，但左右耳没有广播，请确认（90 秒未确认按不在仓处理）")
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "在仓内", piIn).build())
                .addAction(new Notification.Action.Builder(null, "不在仓", piOut).build());

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(ID_CONFIRM, b.build());
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelConfirmNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_CONFIRM);
    }

    private void alert(java.util.List<String> missing, boolean tooFar, int meters) {
        boolean l = missing.contains(Profile.ROLE_L);
        boolean r = missing.contains(Profile.ROLE_R);
        boolean ch = missing.contains(Profile.ROLE_CASE);
        String title = Profile.missingText(l, r, ch);
        String text = tooFar
                ? distanceText() + " · 点此查看"
                : "信号已断开 · 点此查看";

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, pendingFlags());

        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_ALARM);

        if (!store.sound()) b.setSound(null);
        if (!store.vibrate()) b.setVibrate(new long[]{0});

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(ID_PRESENCE, b.build());
            } catch (Exception ignored) {
            }
        }
    }

    private final BroadcastReceiver aclReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            try {
                if (addr != null && addr.equalsIgnoreCase(d.getAddress())) {
                    onStateChanged(BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()));
                }
            } catch (SecurityException ignored) {
            }
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            a2dp = proxy;
            checkState();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            a2dp = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        store = new Store(this);
        createChannels();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CONFIRM_INCASE.equals(action) || ACTION_CONFIRM_NOTINCASE.equals(action)) {
            boolean inCase = ACTION_CONFIRM_INCASE.equals(action);
            askedThisEpisode = true;               // 已回答，本轮不再重复询问
            store.setInCaseConfirmed(inCase);
            store.clearConfirm();
            cancelConfirmNotification();
            alertedKey = null;
            leftBehind = false;
            NotificationManager nm0 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm0 != null) nm0.cancel(ID_PRESENCE);
            startForeground(ID_ONGOING, buildOngoing(lastConnected));
            if (!inCase) {
                rewindTo = confirmAskedAt;        // 用「提问时刻」的定位倒推，扣掉答问耗时
                checkAbsence();                   // 「不在仓」→ 立刻判断并报警
            }
            return START_STICKY;
        }

        if (ACTION_CLEAR_HISTORY.equals(action) || ACTION_KEEP_HISTORY.equals(action)) {
            if (ACTION_CLEAR_HISTORY.equals(action)) store.clearEvents();
            store.setAskClearHistory(false);
            cancelClearPrompt();
            startForeground(ID_ONGOING, buildOngoing(lastConnected));
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            store.setMonitor(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        addr = intent != null && intent.getStringExtra("addr") != null
                ? intent.getStringExtra("addr") : store.target();
        devName = store.targetName();
        if (addr == null && !store.presence()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(ID_ONGOING, buildOngoing(null));
        if (addr != null) startGuard();
        startPresence();
        h.removeCallbacks(poll);
        h.postDelayed(poll, POLL_MS);
        return START_STICKY;
    }

    private void startGuard() {
        if (guardStarted) return;
        guardStarted = true;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(aclReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(aclReceiver, f);
            }
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null) adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        } catch (Exception ignored) {
        }
        checkState();
    }

    private String name() {
        if (devName != null && !devName.isEmpty()) return devName;
        if (addr == null) {
            return "耳机";
        }
        Dev d = store.find(addr);
        return d == null ? "耳机" : d.label();
    }

    private boolean isConnectedNow() {
        if (adapter == null || !adapter.isEnabled()) return false;
        if (a2dp == null) return lastConnected != null && lastConnected;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(addr);
            return a2dp.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            return lastConnected != null && lastConnected;
        }
    }

    private void checkState() {
        if (addr == null) return;
        if (adapter == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        onStateChanged(isConnectedNow());
    }

    private void onStateChanged(boolean connected) {
        if (lastConnected == null) {
            lastConnected = connected;
            updateOngoing(connected);
            return;
        }
        if (connected == lastConnected) return;
        lastConnected = connected;
        updateOngoing(connected);

        if (connected) {
            cancelAlarm();
            cancelAlert();
            store.touchSeen(System.currentTimeMillis());   // 链路连上也算「在线」
            // 耳机重新连上 = 已经取出使用 → 仓内确认作废
            askedThisEpisode = false;
            if (store.inCaseConfirmed() || store.confirmPending()) {
                store.setInCaseConfirmed(false);
                store.clearConfirm();
                cancelConfirmNotification();
            }
        } else {
            scheduleAlarm(store.delaySec() * 1000L);
        }
    }

    private void scheduleAlarm(long delayMs) {
        if (pendingAlarm) return;
        pendingAlarm = true;
        h.postDelayed(alarm, Math.max(0L, delayMs));
    }

    private void cancelAlarm() {
        pendingAlarm = false;
        h.removeCallbacks(alarm);
    }

    private void alert() {
        if (isConnectedNow()) return;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pendingFlags());

        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle(name() + " 已断开")
                .setContentText("蓝牙连接已中断，点此查看最后在线时间")
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_ALARM);

        if (!store.sound()) {
            b.setSound(null);
        }
        if (!store.vibrate()) {
            b.setVibrate(new long[]{0});
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(ID_ALERT, b.build());
            } catch (Exception ignored) {
            }
        }

        if (store.repeat()) {
            h.postDelayed(alarm, REPEAT_MS);
            pendingAlarm = true;
        }
    }

    private void cancelAlert() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_ALERT);
        h.removeCallbacks(alarm);
        pendingAlarm = false;
    }

    private void updateOngoing(Boolean connected) {
        Notification n = buildOngoing(connected);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_ONGOING, n);
    }

    /** 通知里显示的型号：优先用校准读到的产品名，其次用户选设备时的名字，最后用系统蓝牙名。 */
    private String modelName() {
        Profile p = store.profile();
        if (p != null && p.deviceName != null && !p.deviceName.isEmpty()) return p.deviceName;
        String tn = store.targetName();
        if (tn != null && !tn.isEmpty()) return tn;
        if (addr != null && adapter != null) {
            try {
                String n = adapter.getRemoteDevice(addr).getName();
                if (n != null && !n.isEmpty()) return n;
            } catch (Exception ignored) {
            }
        }
        return "耳机";
    }

    private Notification buildOngoing(Boolean connected) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, pendingFlags());

        // 常驻通知用配对的耳机型号 + 遗落检测状态：<型号> — 在附近 / 遗落
        String model = modelName();
        String text;
        if (!store.presence()) {
            text = "后台守护中";
        } else if (store.confirmPending()) {
            text = "待确认：耳机是否在仓内";
        } else if (store.inCaseConfirmed()) {
            text = "在仓内（只守充电仓）";
        } else {
            String dist = distanceText();
            if (leftBehind) {
                String state = lastMissingText.isEmpty() ? "耳机遗落" : lastMissingText;
                text = state + " · " + dist;                 // 与提醒通知同一套文案
            } else {
                text = dist.equals("信号已断开") ? "在附近 · 等待信号" : "在附近 · " + dist;
            }
        }

        return new Notification.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle(model)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private int pendingFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel ongoing = new NotificationChannel(
                CH_ONGOING, "守护状态", NotificationManager.IMPORTANCE_LOW);
        ongoing.setShowBadge(false);
        ongoing.setSound(null, null);
        nm.createNotificationChannel(ongoing);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "断连提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("耳机断开连接时的提醒");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 300, 200, 300});
        alert.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null);
        nm.createNotificationChannel(alert);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        RUNNING = false;
        stopPresence();
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(aclReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        } catch (Exception ignored) {
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
