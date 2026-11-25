package com.budguard;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PROBE = 2;
    private static final int REQ_CAL = 3;

    private Store store;
    private boolean binding = false;

    private TextView tvName;
    private TextView tvAddr;
    private TextView tvLast;
    private TextView tvDelay;
    private TextView tvHint;
    private Switch swMonitor;
    private Switch swPresence;
    private Switch swRepeat;
    private Switch swSound;
    private Switch swVibrate;
    private SeekBar sbDelay;
    private RadioGroup rgLevel;
    private TextView tvBeacon;
    private TextView tvStatus;
    private View confirmCard;
    private View clearCard;
    private Button btnHistory;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        tvName = findViewById(R.id.tv_device_name);
        tvAddr = findViewById(R.id.tv_device_addr);
        tvLast = findViewById(R.id.tv_device_last);
        tvDelay = findViewById(R.id.tv_delay);
        tvHint = findViewById(R.id.tv_hint);
        swMonitor = findViewById(R.id.sw_monitor);
        swPresence = findViewById(R.id.sw_presence);
        tvBeacon = findViewById(R.id.tv_beacon);
        tvStatus = findViewById(R.id.tv_status);
        rgLevel = findViewById(R.id.rg_level);
        confirmCard = findViewById(R.id.confirm_card);
        clearCard = findViewById(R.id.clear_card);
        btnHistory = findViewById(R.id.btn_history);

        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            }
        });
        findViewById(R.id.btn_clear_hist).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAction(BudMonitorService.ACTION_CLEAR_HISTORY);
                clearCard.setVisibility(View.GONE);
                toast("已清除遗落记录");
            }
        });
        findViewById(R.id.btn_keep_hist).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAction(BudMonitorService.ACTION_KEEP_HISTORY);
                clearCard.setVisibility(View.GONE);
            }
        });

        findViewById(R.id.btn_in_case).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendConfirm(true);
            }
        });
        findViewById(R.id.btn_not_in_case).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendConfirm(false);
            }
        });
        swRepeat = findViewById(R.id.sw_repeat);
        swSound = findViewById(R.id.sw_sound);
        swVibrate = findViewById(R.id.sw_vibrate);
        sbDelay = findViewById(R.id.sb_delay);

        Button pick = findViewById(R.id.btn_pick);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_PICK);
            }
        });

        Button find = findViewById(R.id.btn_find);
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String addr = store.target();
                if (addr == null) {
                    toast("先选择一个耳机");
                    return;
                }
                Intent i = new Intent(MainActivity.this, FindActivity.class);
                i.putExtra("addr", addr);
                i.putExtra("name", store.targetName());
                startActivity(i);
            }
        });

        swMonitor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            onMonitorToggled(isChecked);
        });
        swPresence.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            onPresenceToggled(isChecked);
        });
        Button probe = findViewById(R.id.btn_probe);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, ProbeActivity.class), REQ_PROBE);
            }
        });

        Button cal = findViewById(R.id.btn_calibrate);
        cal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, CalibrateActivity.class), REQ_CAL);
            }
        });
        swRepeat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!binding) store.setRepeat(isChecked);
        });
        swSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!binding) store.setSound(isChecked);
        });
        swVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!binding) store.setVibrate(isChecked);
        });

        rgLevel.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (binding) return;
                int lv = checkedId == R.id.rb_lv1 ? 1
                        : checkedId == R.id.rb_lv2 ? 2
                        : checkedId == R.id.rb_lv3 ? 3 : 4;
                store.setAlarmLevel(lv);
            }
        });

        sbDelay.setMax(30);
        sbDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvDelay.setText("断开后 " + progress + " 秒提醒");
                if (fromUser) store.setDelaySec(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        if (!Perms.allGranted(this)) Perms.request(this, 10);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.removeCallbacks(statusTick);
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
    }

    /** 实时状态行：在附近/遗落 + 距离 + 最后在线。每 2 秒刷新一次。 */
    private void updateStatus() {
        if (tvStatus == null) return;
        float m = store.lastDistance();
        long seen = store.lastSeenAt();
        boolean fresh = m >= 0 && seen > 0
                && System.currentTimeMillis() - seen <= 20_000L;

        String dist = fresh
                ? (m < 10 ? String.format(java.util.Locale.US, "约 %.1f 米", m)
                          : "约 " + Math.round(m) + " 米")
                : "信号已断开";

        String state;
        if (store.confirmPending()) state = "待确认（是否在仓内）";
        else if (store.inCaseConfirmed()) state = "在仓内（只守充电仓）";
        else if (!store.presence()) state = "未开启收纳态守护";
        else state = fresh ? "在附近" : "无信号（可能遗落）";

        String ago = seen > 0
                ? "最后在线：" + DateUtils.getRelativeTimeSpanString(seen,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                : "最后在线：未知";

        tvStatus.setText("状态：" + state + "\n距离：" + dist + " · " + ago);
    }

    private void refresh() {
        binding = true;
        Dev d = store.find(store.target());
        if (d == null) {
            tvName.setText("未选择耳机");
            tvAddr.setText("点“选择耳机”扫描并保存你的蓝牙耳机");
            tvLast.setText("");
        } else {
            tvName.setText(d.label());
            tvAddr.setText(d.addr);
            // 最后在线取「后台最近一次听到耳机」的时间，这样耳机重新上线后能被刷新
            long seenAt = store.lastSeenAt();
            long t = seenAt > 0 ? seenAt : d.lastSeen;
            tvLast.setText(t > 0
                    ? "最后在线：" + DateUtils.getRelativeTimeSpanString(t,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    : "最后在线：未知");
        }
        swMonitor.setChecked(store.monitor());
        swMonitor.setEnabled(d != null);
        swPresence.setChecked(store.presence());
        swPresence.setEnabled(store.hasProfile());
        tvBeacon.setText(store.hasProfile()
                ? getString(R.string.beacon_learned, store.profile().summary())
                : getString(R.string.beacon_none));
        int lv = store.alarmLevel();
        rgLevel.check(lv == 1 ? R.id.rb_lv1 : lv == 2 ? R.id.rb_lv2
                : lv == 3 ? R.id.rb_lv3 : R.id.rb_lv4);
        swRepeat.setChecked(store.repeat());
        swSound.setChecked(store.sound());
        swVibrate.setChecked(store.vibrate());
        sbDelay.setProgress(store.delaySec());
        tvDelay.setText("断开后 " + store.delaySec() + " 秒提醒");
        binding = false;

        // 后台正在等「耳机是否在仓内」的确认 → 主页同步显示确认项
        if (confirmCard != null) {
            confirmCard.setVisibility(store.confirmPending() ? View.VISIBLE : View.GONE);
        }

        int eventCount = store.events().size();
        btnHistory.setText(eventCount > 0
                ? getString(R.string.history_open) + "（" + eventCount + "）"
                : getString(R.string.history_open));
        if (clearCard != null) {
            clearCard.setVisibility(store.askClearHistory() && eventCount > 0
                    ? View.VISIBLE : View.GONE);
        }

        // 界面状态与后台服务保持一致
        if (needService()) {
            ensureService(true);
        } else if (BudMonitorService.RUNNING) {
            ensureService(false);
        }

        tvHint.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? "提示：允许通知后才能在断开时收到提醒；本应用完全离线运行，不联网、不上传任何数据。"
                : "提示：本应用完全离线运行，不联网、不上传任何数据。");
    }

    private void onMonitorToggled(boolean on) {
        if (on && store.target() == null) {
            binding = true;
            swMonitor.setChecked(false);
            binding = false;
            toast("先选择一个耳机");
            return;
        }
        store.setMonitor(on);
        syncService();
        toast(on ? "已开启连接态遗落提醒" : "已关闭连接态遗落提醒");
    }

    private void onPresenceToggled(boolean on) {
        if (on && !store.hasProfile()) {
            binding = true;
            swPresence.setChecked(false);
            binding = false;
            toast("先点“学习耳机信标”扫一次");
            return;
        }
        store.setPresence(on);
        syncService();
        toast(on ? "已开启收纳态遗落提醒（充电仓内也生效）" : "已关闭收纳态遗落提醒");
    }

    private boolean needService() {
        return store.monitor() || store.presence();
    }

    private void syncService() {
        ensureService(needService());
    }

    private void ensureService(boolean on) {
        Intent i = new Intent(this, BudMonitorService.class);
        i.setAction(on ? BudMonitorService.ACTION_START : BudMonitorService.ACTION_STOP);
        if (on) {
            // 启动前台服务必须走 startForegroundService（服务会立刻 startForeground）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } else {
            // 停止时用普通 startService，避免未调用 startForeground 被判超时
            startService(i);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            String addr = data.getStringExtra("addr");
            String name = data.getStringExtra("name");
            if (addr != null) {
                store.put(new Dev(name == null ? "" : name, addr, System.currentTimeMillis()));
                store.setTarget(addr);
                if (store.monitor()) onMonitorToggled(true);
                refresh();
            }
        }
    }

    private void sendAction(String action) {
        Intent i = new Intent(this, BudMonitorService.class);
        i.setAction(action);
        startService(i);
    }

    private void sendConfirm(boolean inCase) {
        Intent i = new Intent(this, BudMonitorService.class);
        i.setAction(inCase ? BudMonitorService.ACTION_CONFIRM_INCASE
                : BudMonitorService.ACTION_CONFIRM_NOTINCASE);
        startService(i);
        if (confirmCard != null) confirmCard.setVisibility(View.GONE);
        toast(inCase ? "已记录：耳机在仓内" : "已记录：耳机不在仓内");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
