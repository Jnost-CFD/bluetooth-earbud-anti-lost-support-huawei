package com.budguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 轻量本地存储：设备列表 + 守护设置（SharedPreferences + JSON，无第三方依赖）。 */
public final class Store {

    private static final String PREF = "budguard";
    private static final int MAX_DEVICES = 20;

    private final SharedPreferences sp;

    public Store(Context c) {
        this.sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 设备列表 ----------

    public List<Dev> devices() {
        List<Dev> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(Dev.fromJson(a.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public Dev find(String addr) {
        if (addr == null) return null;
        for (Dev d : devices()) {
            if (addr.equalsIgnoreCase(d.addr)) return d;
        }
        return null;
    }

    public void put(Dev dev) {
        if (dev == null || dev.addr.isEmpty()) return;
        List<Dev> list = devices();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).addr.equalsIgnoreCase(dev.addr)) {
                list.set(i, dev);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(0, dev);
        while (list.size() > MAX_DEVICES) list.remove(list.size() - 1);
        save(list);
    }

    public void remove(String addr) {
        if (addr == null) return;
        List<Dev> list = devices();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).addr.equalsIgnoreCase(addr)) list.remove(i);
        }
        save(list);
        if (addr.equalsIgnoreCase(target())) setTarget(null);
    }

    public void touch(String addr, String name) {
        Dev old = find(addr);
        String label = (name != null && !name.isEmpty()) ? name : (old == null ? "" : old.name);
        put(new Dev(label, addr, System.currentTimeMillis()));
    }

    private void save(List<Dev> list) {
        JSONArray a = new JSONArray();
        for (Dev d : list) a.put(d.toJson());
        sp.edit().putString("devices", a.toString()).apply();
    }

    // ---------- 守护设置 ----------

    public String target() {
        return sp.getString("target", null);
    }

    public void setTarget(String addr) {
        sp.edit().putString("target", addr).apply();
    }

    public String targetName() {
        Dev d = find(target());
        return d == null ? null : d.label();
    }

    public boolean monitor() {
        return sp.getBoolean("monitor", false);
    }

    public void setMonitor(boolean on) {
        sp.edit().putBoolean("monitor", on).apply();
    }

    /** 断开后延时几秒才提醒（防抖，默认 5 秒）。 */
    public int delaySec() {
        return sp.getInt("delay", 5);
    }

    public void setDelaySec(int v) {
        sp.edit().putInt("delay", v).apply();
    }

    /** 未重新连接时是否循环提醒（默认开）。 */
    public boolean repeat() {
        return sp.getBoolean("repeat", true);
    }

    public void setRepeat(boolean on) {
        sp.edit().putBoolean("repeat", on).apply();
    }

    public boolean sound() {
        return sp.getBoolean("sound", true);
    }

    public void setSound(boolean on) {
        sp.edit().putBoolean("sound", on).apply();
    }

    public boolean vibrate() {
        return sp.getBoolean("vibrate", true);
    }

    public void setVibrate(boolean on) {
        sp.edit().putBoolean("vibrate", on).apply();
    }

    // ---------- 收纳态（充电仓）遗落提醒：画像 + 距离门限 ----------

    public Profile profile() {
        return Profile.fromJson(sp.getString("profile", ""));
    }

    public void setProfile(Profile p) {
        sp.edit().putString("profile", p == null ? "" : p.toJson().toString()).apply();
    }

    public boolean hasProfile() {
        return !profile().isEmpty();
    }

    // ---------- 触发距离档位 ----------

    /**
     * 触发档位：
     * 1 = 形影不离（< 1m）
     * 2 = 随身携带（2m）
     * 3 = 待在附近（5m）
     * 4 = 完全断开（不做距离判断，只看信号是否彻底消失）
     */
    public int alarmLevel() {
        int lv = sp.getInt("alarm_level", 0);
        if (lv >= 1 && lv <= 4) return lv;
        // 从旧的连续距离设置迁移
        int m = sp.getInt("alarm_m", 5);
        int mapped = m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 3 : 4;
        sp.edit().putInt("alarm_level", mapped).apply();
        return mapped;
    }

    public void setAlarmLevel(int lv) {
        sp.edit().putInt("alarm_level", lv).apply();
    }

    /** 档位对应的米数；0 = 不做距离判断。 */
    public int alarmMeters() {
        switch (alarmLevel()) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 5;
            default: return 0;
        }
    }

    public static String levelName(int lv) {
        switch (lv) {
            case 1: return "形影不离";
            case 2: return "随身携带";
            case 3: return "待在附近";
            default: return "完全断开";
        }
    }

    public boolean presence() {
        return sp.getBoolean("presence", false);
    }

    public void setPresence(boolean on) {
        sp.edit().putBoolean("presence", on).apply();
    }

    /** 多少秒看不到信标才算「不在身边」。 */
    public int absenceSec() {
        return sp.getInt("absence_sec", 45);
    }

    public void setAbsenceSec(int v) {
        sp.edit().putInt("absence_sec", v).apply();
    }

    // ---------- 「耳机在充电仓内」的确认状态 ----------

    /** 用户已确认耳机在仓内：此时不报左耳/右耳遗落。 */
    public boolean inCaseConfirmed() {
        return sp.getBoolean("in_case", false);
    }

    public void setInCaseConfirmed(boolean v) {
        sp.edit().putBoolean("in_case", v).apply();
    }

    /** 正在等用户确认（通知/主页都显示确认项）。 */
    public boolean confirmPending() {
        return sp.getBoolean("confirm_pending", false);
    }

    // ---------- 实时状态（供通知与主页显示） ----------

    /** 最近一次测得的距离（米）；-1 表示未知。 */
    public float lastDistance() {
        return sp.getFloat("last_dist", -1f);
    }

    /** 最近一次「听到耳机」的时间戳。 */
    public long lastSeenAt() {
        return sp.getLong("last_seen_at", 0L);
    }

    public void setLastDistance(float meters, long at) {
        sp.edit().putFloat("last_dist", meters).putLong("last_seen_at", at).apply();
    }

    /** 只更新「最近一次见到」的时间（例如链路连接事件）。 */
    public void touchSeen(long at) {
        sp.edit().putLong("last_seen_at", at).apply();
    }

    // ---------- 遗落记录（最多 10 条 / 48 小时） ----------

    private static final int MAX_EVENTS = 10;
    private static final long KEEP_MS = 48L * 3600_000L;

    public java.util.List<LeaveEvent> events() {
        java.util.List<LeaveEvent> out = new java.util.ArrayList<>();
        long keepAfter = System.currentTimeMillis() - KEEP_MS;
        try {
            JSONArray a = new JSONArray(sp.getString("events", "[]"));
            for (int i = 0; i < a.length(); i++) {
                LeaveEvent e = LeaveEvent.fromJson(a.getJSONObject(i));
                if (e.at >= keepAfter) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void addEvent(LeaveEvent e) {
        java.util.List<LeaveEvent> list = events();
        list.add(0, e);                              // 新记录在前
        while (list.size() > MAX_EVENTS) list.remove(list.size() - 1);
        saveEvents(list);
    }

    public void clearEvents() {
        saveEvents(new java.util.ArrayList<LeaveEvent>());
    }

    public void updateEventAddress(long at, String address) {
        java.util.List<LeaveEvent> list = events();
        for (LeaveEvent e : list) {
            if (e.at == at) {
                e.address = address;
                break;
            }
        }
        saveEvents(list);
    }

    private void saveEvents(java.util.List<LeaveEvent> list) {
        JSONArray a = new JSONArray();
        for (LeaveEvent e : list) a.put(e.toJson());
        sp.edit().putString("events", a.toString()).apply();
    }

    /** 回到身边后是否要询问「清除遗落记录」。 */
    public boolean askClearHistory() {
        return sp.getBoolean("ask_clear", false);
    }

    public void setAskClearHistory(boolean v) {
        sp.edit().putBoolean("ask_clear", v).apply();
    }

    public long confirmDeadline() {
        return sp.getLong("confirm_deadline", 0L);
    }

    public void startConfirm(long deadline) {
        sp.edit().putBoolean("confirm_pending", true)
                .putLong("confirm_deadline", deadline).apply();
    }

    public void clearConfirm() {
        sp.edit().putBoolean("confirm_pending", false)
                .putLong("confirm_deadline", 0L).apply();
    }
}
