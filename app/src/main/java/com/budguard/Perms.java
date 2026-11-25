package com.budguard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** 权限集合：按系统版本裁剪。 */
public final class Perms {

    public static String[] required(Context c) {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return p.toArray(new String[0]);
    }

    public static boolean allGranted(Context c) {
        for (String p : required(c)) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static void request(Activity a, int code) {
        String[] missing = missing(a);
        if (missing.length > 0) a.requestPermissions(missing, code);
    }

    private static String[] missing(Context c) {
        List<String> out = new ArrayList<>();
        for (String p : required(c)) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out.toArray(new String[0]);
    }
}
