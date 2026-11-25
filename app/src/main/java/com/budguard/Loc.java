package com.budguard;

import org.json.JSONObject;

import java.util.Locale;

/** 一次定位采样。 */
public final class Loc {

    public final double lat;
    public final double lng;
    /** 精度半径（米），越小越可信。 */
    public final float acc;
    public final long at;
    public final String src;

    public Loc(double lat, double lng, float acc, long at, String src) {
        this.lat = lat;
        this.lng = lng;
        this.acc = acc;
        this.at = at;
        this.src = src == null ? "" : src;
    }

    public boolean valid() {
        return !(lat == 0 && lng == 0) && acc >= 0;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("acc", (double) acc);
            o.put("at", at);
            o.put("src", src);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Loc fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            return new Loc(o.getDouble("lat"), o.getDouble("lng"),
                    (float) o.optDouble("acc", 999), o.optLong("at", 0), o.optString("src", ""));
        } catch (Exception e) {
            return null;
        }
    }

    public String coord() {
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    public String accText() {
        if (acc < 0) return "精度未知";
        if (acc <= 10) return "精度约 " + Math.round(acc) + " 米（好）";
        if (acc <= 30) return "精度约 " + Math.round(acc) + " 米（一般）";
        return "精度约 " + Math.round(acc) + " 米（差）";
    }

    public String mapUrl() {
        return "geo:" + lat + "," + lng + "?q=" + lat + "," + lng;
    }
}
