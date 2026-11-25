package com.budguard;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动校准，分三步：
 *
 *   第 1 步（只识别）：耳机全部放仓、盖好 → 扫 15 秒，过滤掉 Apple/车钥匙/门锁等噪声，
 *                      把仍在广播的那个地址识别为充电仓。这一步只展示结果，不写任何设置。
 *   第 2 步：只取出左耳 → 新出现的地址 = 左耳
 *   第 3 步：左耳放回、只取右耳 → 新出现的地址 = 右耳
 *
 * 角色由物理动作唯一确定，不需要任何逐条点选。
 */
public class CalibrateActivity extends Activity {

    private static final long SCAN_MS = 15_000L;
    private static final int PHASE_IDENTIFY = 0;
    private static final int PHASE_LEFT = 1;
    private static final int PHASE_RIGHT = 2;

    private final Map<String, Set<String>> seen = new LinkedHashMap<>();
    private final Map<String, Integer> bestRssi = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();

    private BluetoothAdapter bt;
    private BluetoothLeScanner scanner;
    private final Handler h = new Handler(Looper.getMainLooper());

    private int phase = PHASE_IDENTIFY;
    private boolean showingResult;
    private boolean scanning;
    private long deadline;
    private Set<String> phaseStart = new LinkedHashSet<>();

    private String caseAddr;
    private String leftAddr;
    private String rightAddr;
    private String resultText = "";

    private TextView title;
    private TextView hint;
    private ProgressBar bar;
    private Button action;

    private final ScanCallback cb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            Beacon b = Beacon.from(result);
            if (b.addr == null || b.addr.isEmpty()) return;
            Set<String> s = seen.get(b.addr);
            if (s == null) {
                s = new LinkedHashSet<>();
                seen.put(b.addr, s);
            }
            s.addAll(b.sigs);
            Integer old = bestRssi.get(b.addr);
            if (old == null || b.rssi > old) bestRssi.put(b.addr, b.rssi);
            if (b.name != null && !b.name.isEmpty() && !names.containsKey(b.addr)) {
                names.put(b.addr, b.name);
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                finishPhase();
                return;
            }
            bar.setProgress((int) (100 - left * 100 / SCAN_MS));
            hint.setText(hintForScan() + "\n剩余 " + (left / 1000 + 1) + " 秒 · 已见 "
                    + seen.size() + " 个广播源");
            h.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibrate);

        title = findViewById(R.id.cal_title);
        hint = findViewById(R.id.cal_hint);
        bar = findViewById(R.id.cal_bar);
        action = findViewById(R.id.cal_action);

        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) return;
                if (showingResult) {
                    if (phase == PHASE_RIGHT) {
                        save();
                        return;
                    }
                    showingResult = false;
                    if (phase == PHASE_IDENTIFY) phase = PHASE_LEFT;
                    else if (phase == PHASE_LEFT) phase = PHASE_RIGHT;
                    render();
                    return;
                }
                startPhase();
            }
        });

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        if (!Perms.allGranted(this)) Perms.request(this, 300);
        render();
    }

    // ---------------- 界面 ----------------

    private String hintForScan() {
        switch (phase) {
            case PHASE_IDENTIFY:
                return "把两只耳机都放进充电仓、盖好盖子，放在手机旁边";
            case PHASE_LEFT:
                return "只取出【左耳】拿在手里，右耳留在仓里、盖好";
            default:
                return "把左耳放回仓里，再只取出【右耳】拿在手里";
        }
    }

    private void render() {
        bar.setProgress(0);
        action.setEnabled(true);

        if (showingResult) {
            title.setText("第 " + (phase + 1) + " 步结果");
            hint.setText(resultText);
            action.setText(phase == PHASE_IDENTIFY ? "下一步：取出左耳"
                    : phase == PHASE_LEFT ? "下一步：取出右耳" : "保存");
            return;
        }

        switch (phase) {
            case PHASE_IDENTIFY:
                title.setText("第 1 步 / 3：只识别");
                hint.setText(hintForScan() + "\n点下面按钮扫 15 秒（只识别信号，不改任何设置）");
                action.setText("开始识别");
                break;
            case PHASE_LEFT:
                title.setText("第 2 步 / 3：左耳");
                hint.setText(hintForScan() + "\n点下面按钮扫 15 秒");
                action.setText("扫描左耳");
                break;
            default:
                title.setText("第 3 步 / 3：右耳");
                hint.setText(hintForScan() + "\n点下面按钮扫 15 秒");
                action.setText("扫描右耳");
                break;
        }
    }

    // ---------------- 扫描 ----------------

    private void startPhase() {
        if (bt == null || !bt.isEnabled()) {
            toast("请先打开蓝牙");
            return;
        }
        phaseStart = new LinkedHashSet<>(seen.keySet());
        try {
            scanner = bt.getBluetoothLeScanner();
            if (scanner == null) return;
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
            return;
        }
        scanning = true;
        deadline = System.currentTimeMillis() + SCAN_MS;
        action.setEnabled(false);
        action.setText("扫描中…");
        h.post(tick);
    }

    private void stopScan() {
        scanning = false;
        h.removeCallbacks(tick);
        try {
            if (scanner != null) scanner.stopScan(cb);
        } catch (Exception ignored) {
        }
    }

    /** 只认华为耳机通道，Apple 厂商数据一律排除。 */
    private boolean isOurDevice(String addr) {
        Set<String> sigs = seen.get(addr);
        if (sigs == null) return false;
        boolean huawei = false;
        for (String s : sigs) {
            if (s.startsWith("mfg:76:")) return false;      // Apple（0x004C）
            if (s.startsWith("sd:fdee")) huawei = true;     // 华为耳机专属服务数据
        }
        return huawei;
    }

    private List<String> ourDevices() {
        List<String> out = new ArrayList<>();
        for (String a : seen.keySet()) if (isOurDevice(a)) out.add(a);
        java.util.Collections.sort(out, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                Integer ra = bestRssi.get(a), rb = bestRssi.get(b);
                return Integer.compare(rb == null ? -127 : rb, ra == null ? -127 : ra);
            }
        });
        return out;
    }

    private void finishPhase() {
        stopScan();

        if (phase == PHASE_IDENTIFY) {
            List<String> ours = ourDevices();
            if (ours.isEmpty()) {
                toast("没识别到华为耳机广播。确认耳机在仓里、盖好、蓝牙已开");
                render();
                return;
            }
            caseAddr = ours.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("识别到 ").append(ours.size()).append(" 个属于你的信号：\n");
            for (String a : ours) {
                sb.append(a).append("  ").append(bestRssi.get(a)).append(" dBm");
                if (a.equals(caseAddr)) sb.append("   ← 充电仓");
                sb.append('\n');
            }
            sb.append("\n（已过滤 Apple 设备与车钥匙、门锁等噪声）");
            resultText = sb.toString();
            showingResult = true;
            render();
            return;
        }

        Set<String> added = new LinkedHashSet<>(seen.keySet());
        added.retainAll(ourDevices());                 // 只在新出现的「我们的设备」里挑
        added.removeAll(phaseStart);

        if (added.isEmpty()) {
            toast(phase == PHASE_LEFT
                    ? "没发现新的耳机信号。确认只取出左耳、右耳留在仓里"
                    : "没发现新的耳机信号。确认左耳已放回、只取出右耳");
            render();
            return;
        }

        String addr = added.iterator().next();
        if (phase == PHASE_LEFT) {
            leftAddr = addr;
            resultText = "左耳已识别：\n" + addr + "  " + bestRssi.get(addr) + " dBm";
            showingResult = true;
        } else {
            rightAddr = addr;
            resultText = "右耳已识别：\n" + addr + "  " + bestRssi.get(addr) + " dBm";
            showingResult = true;
        }
        render();
    }

    private void save() {
        Profile p = new Profile();
        int best = -127;
        for (String a : seen.keySet()) {
            Profile.Comp c;
            if (a.equals(leftAddr)) c = new Profile.Comp(a, Profile.ROLE_L);
            else if (a.equals(rightAddr)) c = new Profile.Comp(a, Profile.ROLE_R);
            else if (a.equals(caseAddr)) c = new Profile.Comp(a, Profile.ROLE_CASE);
            else continue;                                   // 未参与识别的地址不写入
            c.sigs.addAll(seen.get(a));
            if (c.sigs.isEmpty()) continue;
            p.comps.add(c);
            Integer r = bestRssi.get(a);
            if (r != null && r > best) best = r;
        }
        if (p.comps.isEmpty()) {
            toast("没有可用数据");
            return;
        }
        p.txPower = best - 10.5f;
        p.deviceName = pickName();
        p.learnedAt = System.currentTimeMillis();
        new Store(this).setProfile(p);

        StringBuilder sb = new StringBuilder();
        for (Profile.Comp c : p.comps) sb.append(Profile.roleName(c.role)).append(' ');
        toast("校准完成：" + sb.toString().trim());
        setResult(RESULT_OK, new Intent());
        finish();
    }

    /** 从校准过程中抓到的产品名里挑一个（优先充电仓那条，它通常带 Complete Local Name）。 */
    private String pickName() {
        String n = names.get(caseAddr);
        if (n != null && !n.isEmpty()) return n;
        String[] order = new String[]{leftAddr, rightAddr};
        for (String a : order) {
            String x = names.get(a);
            if (x != null && !x.isEmpty()) return x;
        }
        for (String x : names.values()) {
            if (x != null && !x.isEmpty()) return x;
        }
        return "";
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (!Perms.allGranted(this)) toast("需要蓝牙权限才能校准");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
