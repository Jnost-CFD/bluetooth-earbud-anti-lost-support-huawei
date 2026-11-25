package com.budguard;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * 用加速度计判断「人是不是在走动」，无需任何权限。
 * 只有手机确实移动过，才允许触发遗落提醒 —— 否则把耳机放在桌上、人坐在旁边也会被误报。
 */
public final class MotionDetector implements SensorEventListener {

    private static final float MOVE_THRESHOLD = 1.6f;   // m/s^2 偏离重力
    private static final long MOVE_WINDOW_MS = 90_000L;

    private final SensorManager sm;
    private Sensor accel;
    private volatile long lastMoveAt;

    public MotionDetector(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        if (sm == null) return;
        if (accel == null) accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double mag = Math.sqrt(x * x + y * y + z * z);
        if (Math.abs(mag - SensorManager.GRAVITY_EARTH) > MOVE_THRESHOLD) {
            lastMoveAt = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** 最近 90 秒内手机是否动过。 */
    public boolean movedRecently() {
        return SystemClock.elapsedRealtime() - lastMoveAt < MOVE_WINDOW_MS;
    }

    /** 最近一次检测到移动的时间（开机毫秒），0 表示没动过。 */
    public long lastMoveAt() {
        return lastMoveAt;
    }
}
