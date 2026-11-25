package com.budguard;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import java.util.List;
import java.util.Locale;

/**
 * 反向地理编码：经纬度 → 中文地址。
 *
 * 用系统 Geocoder（Geocoder.isPresent() 为假时直接返回空串）。
 * 华为/荣耀设备上通常由 HMS 提供；若系统没有可用的地理编码后端，
 * 界面会退化为显示经纬度，不会报错。
 *
 * 注意：getFromLocation 是阻塞调用，必须在后台线程执行。
 */
public final class Geocode {

    private Geocode() {
    }

    public static String reverse(Context c, Loc loc) {
        if (loc == null || !loc.valid()) return "";
        try {
            if (!Geocoder.isPresent()) return "";
            Geocoder g = new Geocoder(c.getApplicationContext(), Locale.CHINA);
            List<Address> list = g.getFromLocation(loc.lat, loc.lng, 1);
            if (list == null || list.isEmpty()) return "";
            Address a = list.get(0);
            StringBuilder sb = new StringBuilder();
            append(sb, a.getAdminArea());       // 省
            append(sb, a.getLocality());        // 市
            if (a.getLocality() == null) append(sb, a.getSubAdminArea());
            append(sb, a.getSubLocality());     // 区/街道
            append(sb, a.getThoroughfare());    // 路
            append(sb, a.getSubThoroughfare()); // 号
            String s = sb.toString();
            if (s.isEmpty()) {
                s = a.getAddressLine(0) == null ? "" : a.getAddressLine(0);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.trim().isEmpty()) return;
        if (sb.indexOf(part) >= 0) return;      // 去重：有些后端省/市重复
        sb.append(part);
    }
}
