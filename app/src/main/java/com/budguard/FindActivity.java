package com.budguard;

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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 离线近场查找：只被动接收耳机自己发出的蓝牙广播，全程不联网、不配对，
 * 用信号强度（RSSI）估算远近，并用“越近越急促”的震动 / 提示音引导寻找。
 */
public class FindActivity extends Activity {

    /** 1 米处的参考信号强度（常见耳机实测区间 -55 ~ -65 dBm）。 */
    private static final double TX_POWER = -59.0;
    /** 室内路径损耗指数。 */
    private static final double PATH_LOSS_N = 2.0;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner leScanner;
    private String targetAddr;
    private String targetName;

    private double smoothedRssi = -127;
    private double prevDistance = -1;
    private long lastSeen;
    private boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;
    private ToneGenerator tone;

    private ProgressBar bar;
    private TextView bigDist;
    private TextView bigHint;
    private TextView bigRssi;

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String a;
            try {
                a = d.getAddress();
            } catch (SecurityException e) {
                return;
            }
            if (targetAddr != null && targetAddr.equalsIgnoreCase(a)) {
                onSignal(result.getRssi());
            }
        }
    };

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            short r = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
            if (d == null) return;
            try {
                if (targetAddr != null && targetAddr.equalsIgnoreCase(d.getAddress())) onSignal(r);
            } catch (SecurityException ignored) {
            }
        }
    };

    private final Runnable pulse = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick();
            h.postDelayed(this, 120);
        }
    };

    private final Runnable beeper = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long since = System.currentTimeMillis() - lastSeen;
            if (since < 6000) {
                int p = progress();
                long interval = 120 + (long) ((100 - p) * 9);   // 越近越密
                if (vibrator != null) {
                    try {
                        vibrator.vibrate(VibrationEffect.createOneShot(35 + p / 4, VibrationEffect.DEFAULT_AMPLITUDE));
                    } catch (Exception ignored) {
                    }
                }
                if (tone != null) {
                    try {
                        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70);
                    } catch (Exception ignored) {
                    }
                }
                h.postDelayed(this, interval);
            } else {
                h.postDelayed(this, 300);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_find);

        targetAddr = getIntent().getStringExtra("addr");
        targetName = getIntent().getStringExtra("name");

        bar = findViewById(R.id.find_bar);
        bigDist = findViewById(R.id.find_dist);
        bigHint = findViewById(R.id.find_hint);
        bigRssi = findViewById(R.id.find_rssi);

        TextView title = findViewById(R.id.find_title);
        title.setText(targetName == null || targetName.isEmpty() ? "查找耳机" : "查找：" + targetName);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        } catch (Exception ignored) {
        }

        Button stop = findViewById(R.id.find_stop);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) {
            bigHint.setText("本机没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            bigHint.setText("请先打开系统蓝牙");
            return;
        }
        startScan();
        running = true;
        h.post(pulse);
        h.post(beeper);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        h.removeCallbacks(pulse);
        h.removeCallbacks(beeper);
        stopScan();
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) {
            try {
                tone.release();
            } catch (Exception ignored) {
            }
            tone = null;
        }
    }

    private void startScan() {
        try {
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner != null) {
                ScanSettings s = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                leScanner.startScan(null, s, bleCallback);
            }
            registerReceiver(classicReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            adapter.startDiscovery();
        } catch (SecurityException ignored) {
        }
        bigHint.setText("正在搜索信号…保持移动，越近提示越急促");
    }

    private void stopScan() {
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

    private void onSignal(int rssi) {
        lastSeen = System.currentTimeMillis();
        if (smoothedRssi < -126) smoothedRssi = rssi;
        else smoothedRssi = smoothedRssi * 0.7 + rssi * 0.3;
    }

    private int progress() {
        double d = distance();
        double p = 100.0 * (1.0 - (Math.log10(d) - Math.log10(0.4)) / (Math.log10(12.0) - Math.log10(0.4)));
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return (int) Math.round(p);
    }

    private double distance() {
        if (smoothedRssi < -126) return 30.0;
        double d = Math.pow(10.0, (TX_POWER - smoothedRssi) / (10.0 * PATH_LOSS_N));
        if (d < 0.2) d = 0.2;
        if (d > 30) d = 30;
        return d;
    }

    private void tick() {
        long since = System.currentTimeMillis() - lastSeen;
        int p = progress();
        bar.setProgress(p);
        if (since > 6000) {
            bigDist.setText("--");
            bigHint.setText("暂时没有信号\n请让耳机处于可被发现状态（取出/开盖/配对模式）并靠近搜索");
            bigRssi.setText("等待广播…");
            prevDistance = -1;
        } else {
            double d = distance();
            // 趋势：与上一次刷新比较，判断「正在靠近 / 正在远离」，这是找耳机最实用的方向提示
            String trend = "";
            if (prevDistance > 0) {
                double delta = prevDistance - d;
                if (delta > 0.25) trend = "\n↑ 正在靠近";
                else if (delta < -0.25) trend = "\n↓ 正在远离，换个方向";
            }
            prevDistance = d;
            bigDist.setText(d < 1.0 ? String.format(Locale.US, "%.1f 米内", d) : String.format(Locale.US, "约 %.0f 米", d));
            bigHint.setText((p > 80 ? "就在附近！" : p > 50 ? "越来越近了" : p > 20 ? "有信号，继续移动" : "信号很弱，换个方向试试") + trend);
            bigRssi.setText(String.format(Locale.US, "%d dBm", (int) smoothedRssi));
        }
    }
}
