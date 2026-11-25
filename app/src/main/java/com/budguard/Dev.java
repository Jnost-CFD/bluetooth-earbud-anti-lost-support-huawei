package com.budguard;

import org.json.JSONObject;

/** 一个蓝牙设备（耳机）。 */
public final class Dev {

    public final String name;
    public final String addr;
    public long lastSeen;

    public Dev(String name, String addr, long lastSeen) {
        this.name = name == null ? "" : name;
        this.addr = addr == null ? "" : addr;
        this.lastSeen = lastSeen;
    }

    public String label() {
        return name.isEmpty() ? addr : name;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("addr", addr);
            o.put("seen", lastSeen);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Dev fromJson(JSONObject o) {
        return new Dev(o.optString("name"), o.optString("addr"), o.optLong("seen", 0L));
    }

    @Override
    public String toString() {
        return label();
    }
}
