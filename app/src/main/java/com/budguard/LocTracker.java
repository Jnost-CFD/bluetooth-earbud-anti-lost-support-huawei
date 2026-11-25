package com.budguard;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后台定位采样（GPS + 网络，1 分钟一次）。
 * 用系统 LocationManager，不依赖 GMS——荣耀/华为设备上没有 Google 定位服务。
 */
public final class LocTracker implements LocationListener {

    private static final long MIN_TIME_MS = 20_000L;
    private static final float MIN_DISTANCE_M = 0f;
    private static final int MAX_SAMPLES = 40;

    private final LocationManager lm;
    private final List<Loc> recent = new ArrayList<>();
    private Loc last;
    private boolean running;

    public LocTracker(Context c) {
        this.lm = (LocationManager) c.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (lm == null || running) return;
        running = true;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                lm.requestLocationUpdates(p, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper());
                Location l = lm.getLastKnownLocation(p);
                if (l != null) onLocationChanged(l);
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        running = false;
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        float acc = l.hasAccuracy() ? l.getAccuracy() : 999f;
        Loc loc = new Loc(l.getLatitude(), l.getLongitude(), acc,
                l.getTime() > 0 ? l.getTime() : System.currentTimeMillis(), l.getProvider());
        synchronized (this) {
            last = loc;
            recent.add(loc);
            while (recent.size() > MAX_SAMPLES) recent.remove(0);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    public synchronized Loc last() {
        return last;
    }

    public synchronized List<Loc> recent() {
        return Collections.unmodifiableList(new ArrayList<>(recent));
    }

    /** 取「不晚于 atMs 的最近一次定位」，用于还原「最后一次离得近时」的位置。 */
    public synchronized Loc lastBefore(long atMs) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (recent.get(i).at <= atMs) return recent.get(i);
        }
        return null;
    }

    public synchronized void addExternal(Loc loc) {
        if (loc == null || !loc.valid()) return;
        last = loc;
        recent.add(loc);
        while (recent.size() > MAX_SAMPLES) recent.remove(0);
    }
}
