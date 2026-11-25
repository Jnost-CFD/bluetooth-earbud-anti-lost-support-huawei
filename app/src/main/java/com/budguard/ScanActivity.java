package com.budguard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 扫描附近蓝牙设备（BLE 广播 + 经典蓝牙发现），选中后保存为守护目标。 */
public class ScanActivity extends Activity {

    private final Map<String, Dev> found = new LinkedHashMap<>();
    private final Map<String, Integer> rssiMap = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner leScanner;
    private ArrayAdapter<String> listAdapter;
    private final List<Dev> shown = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean scanning = false;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 600);
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String name = "";
            try {
                name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            } catch (Exception ignored) {
            }
            onFound(d, name, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            toast("BLE 扫描失败: " + errorCode);
            scanning = false;
        }
    };

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            short r = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
            if (d != null) onFound(d, d.getName(), r);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2, android.R.id.text1);
        ListView lv = findViewById(R.id.scan_list);
        lv.setAdapter(listAdapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                Dev d = shown.get(position);
                new Store(ScanActivity.this).put(new Dev(d.name, d.addr, System.currentTimeMillis()));
                Intent data = new Intent();
                data.putExtra("addr", d.addr);
                data.putExtra("name", d.name);
                setResult(RESULT_OK, data);
                finish();
            }
        });

        findViewById(R.id.scan_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScan();
                finish();
            }
        });

        if (!Perms.allGranted(this)) {
            Perms.request(this, 100);
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            toast("本机没有蓝牙适配器");
            finish();
            return;
        }
        if (!adapter.isEnabled()) {
            toast("请先打开蓝牙");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    private void onFound(BluetoothDevice d, String name, int rssi) {
        String addr;
        try {
            addr = d.getAddress();
        } catch (SecurityException e) {
            return;
        }
        String label = name;
        if (label == null || label.trim().isEmpty()) {
            try {
                label = d.getName();
            } catch (SecurityException ignored) {
            }
        }
        if (label == null) label = "";
        rssiMap.put(addr, rssi);
        Dev old = found.get(addr);
        if (old == null) {
            found.put(addr, new Dev(label, addr, System.currentTimeMillis()));
        } else if (old.name.isEmpty() && !label.isEmpty()) {
            found.put(addr, new Dev(label, addr, old.lastSeen));
        }
    }

    private void render() {
        List<Dev> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                int ra = rssiMap.containsKey(a.addr) ? rssiMap.get(a.addr) : -999;
                int rb = rssiMap.containsKey(b.addr) ? rssiMap.get(b.addr) : -999;
                return Integer.compare(rb, ra);
            }
        });
        shown.clear();
        shown.addAll(list);
        listAdapter.clear();
        for (Dev d : list) {
            int r = rssiMap.containsKey(d.addr) ? rssiMap.get(d.addr) : -127;
            String title = d.name.isEmpty() ? "未知设备" : d.name;
            String sub = String.format(Locale.US, "%s · %d dBm", d.addr, r);
            listAdapter.add(title + "\n" + sub);
        }
        listAdapter.notifyDataSetChanged();
        TextView tv = findViewById(R.id.scan_hint);
        tv.setText(scanning ? "正在扫描… 点击设备即可选中（" + list.size() + "）"
                : "扫描已停止（" + list.size() + "）");
    }

    public void startScan() {
        if (adapter == null || scanning) return;
        if (!adapter.isEnabled()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner != null) {
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                leScanner.startScan(null, settings, bleCallback);
            }
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(classicReceiver, f);
            adapter.startDiscovery();
            scanning = true;
        } catch (SecurityException e) {
            toast("缺少蓝牙权限");
        }
        ui.post(refresh);
    }

    private void stopScan() {
        scanning = false;
        ui.removeCallbacks(refresh);
        try {
            if (leScanner != null) leScanner.stopScan(bleCallback);
        } catch (Exception ignored) {
        }
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(classicReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!Perms.allGranted(this)) {
            toast("需要蓝牙权限才能扫描设备");
        } else {
            startScan();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
