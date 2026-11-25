package com.budguard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 探测日志（纯诊断工具，不做学习、不改设置）。
 * 按指纹聚合附近所有广播，用于排查「为什么没识别到」。
 */
public class ProbeActivity extends Activity {

    private static final class Agg {
        String sig;
        String note = "";
        String sample = "";
        String name = "";
        int count;
        int best = -127;
        long last;
        final Set<String> addrs = new LinkedHashSet<>();
    }

    private final Map<String, Agg> aggs = new LinkedHashMap<>();
    private final List<Agg> shown = new ArrayList<>();

    private ArrayAdapter<String> adapter;
    private BluetoothAdapter bt;
    private BluetoothLeScanner scanner;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean scanning;
    private long startedAt;

    private final Runnable render = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            doRender();
            ui.postDelayed(this, 800);
        }
    };

    private final ScanCallback cb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            Beacon b = Beacon.from(result);
            List<String> notes = b.noteList();
            int i = 0;
            for (String sig : b.sigs) {
                Agg a = aggs.get(sig);
                if (a == null) {
                    a = new Agg();
                    a.sig = sig;
                    a.note = i < notes.size() ? notes.get(i) : sig;
                    aggs.put(sig, a);
                }
                a.count++;
                a.last = System.currentTimeMillis();
                if (b.rssi > a.best) a.best = b.rssi;
                if (a.name.isEmpty() && b.name != null && !b.name.isEmpty()) a.name = b.name;
                if (a.sample.isEmpty()) a.sample = b.recordHex;
                if (b.addr != null && !b.addr.isEmpty()) a.addrs.add(b.addr);
                i++;
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            toast("扫描失败: " + errorCode);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_probe);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, android.R.id.text1);
        ListView lv = findViewById(R.id.probe_list);
        lv.setAdapter(adapter);

        findViewById(R.id.probe_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
        findViewById(R.id.probe_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        if (!Perms.allGranted(this)) Perms.request(this, 200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    private void start() {
        if (bt == null || !bt.isEnabled()) {
            ((TextView) findViewById(R.id.probe_hint)).setText("请先打开蓝牙");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            scanner = bt.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.startScan(null, new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build(), cb);
                scanning = true;
                startedAt = System.currentTimeMillis();
                ui.post(render);
            }
        } catch (SecurityException e) {
            toast("缺少蓝牙扫描权限");
        }
    }

    private void stop() {
        scanning = false;
        ui.removeCallbacks(render);
        try {
            if (scanner != null) scanner.stopScan(cb);
        } catch (Exception ignored) {
        }
    }

    private void doRender() {
        shown.clear();
        for (Agg a : aggs.values()) {
            if (a.sig.contains(":*:")) continue;
            shown.add(a);
        }
        Collections.sort(shown, new Comparator<Agg>() {
            @Override
            public int compare(Agg a, Agg b) {
                return Integer.compare(b.count, a.count);
            }
        });
        adapter.clear();
        long now = System.currentTimeMillis();
        for (Agg a : shown) {
            String head = a.note + "   ×" + a.count;
            String sub = "  " + a.best + " dBm · " + ((now - a.last) / 1000) + "s 前"
                    + (a.name.isEmpty() ? "" : " · " + a.name) + "  " + a.sig;
            adapter.add(head + "\n" + sub);
        }
        adapter.notifyDataSetChanged();

        TextView hint = findViewById(R.id.probe_hint);
        long sec = (System.currentTimeMillis() - startedAt) / 1000;
        hint.setText("已发现 " + shown.size() + " 种广播（" + sec + "s）\n"
                + "华为耳机看 SD:fdee；Apple 设备是 MFG:0x004c，识别时会自动排除");
    }

    private String buildLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("BudGuard BLE probe\n");
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("sig 格式: sd:UUID:类型字节:长度 / mfg:厂商ID:类型字节:长度\n\n");
        List<Agg> all = new ArrayList<>(aggs.values());
        Collections.sort(all, new Comparator<Agg>() {
            @Override
            public int compare(Agg a, Agg b) {
                return Integer.compare(b.count, a.count);
            }
        });
        for (Agg a : all) {
            sb.append("----\n");
            sb.append("sig  : ").append(a.sig).append('\n');
            sb.append("note : ").append(a.note).append('\n');
            sb.append("name : ").append(a.name).append('\n');
            sb.append("count: ").append(a.count).append("  best: ").append(a.best).append('\n');
            sb.append("addrs: ").append(a.addrs.toString()).append('\n');
            sb.append("sample: ").append(a.sample).append('\n');
        }
        return sb.toString();
    }

    private void copyLog() {
        String log = buildLog();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("budguard-probe", log));
        try {
            File dir = getExternalFilesDir("probe");
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "probe-" + System.currentTimeMillis() + ".txt");
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(log.getBytes(Charset.forName("UTF-8")));
                fo.close();
                toast("已复制，并保存到 " + f.getAbsolutePath());
                return;
            }
        } catch (Exception ignored) {
        }
        toast("已复制到剪贴板");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (Perms.allGranted(this)) start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
