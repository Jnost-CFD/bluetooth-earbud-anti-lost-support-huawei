package com.budguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 学到的「我的耳机」画像。
 *
 * 一个耳机系统通常对外表现为多个 BLE 地址（左耳 / 右耳 / 充电仓各一个），
 * 这里按「部件」拆开存：每个部件 = 地址 + 角色 + 该部件的指纹集合。
 * 这样某一部件消失时，能说出到底是哪个丢了。
 */
public final class Profile {

    public static final String ROLE_L = "L";
    public static final String ROLE_R = "R";
    public static final String ROLE_CASE = "CASE";
    public static final String ROLE_BUDS = "BUDS";

    public static final class Comp {
        public final String addr;
        public String role;
        public final Set<String> sigs = new LinkedHashSet<>();

        public Comp(String addr, String role) {
            this.addr = addr;
            this.role = role;
        }

        public boolean matches(Set<String> seen) {
            for (String s : seen) if (sigs.contains(s)) return true;
            return false;
        }
    }

    public final List<Comp> comps = new ArrayList<>();
    /** 校准过程中从广播里读到的产品名，例如 "HUAWEI FreeBuds Pro 3"。 */
    public String deviceName = "";
    /** 1 米处的参考 RSSI（A），学习时自动标定。 */
    public float txPower = -59f;
    public float noise = 3f;
    public long learnedAt;

    public boolean isEmpty() {
        return comps.isEmpty();
    }

    /** 所有部件的指纹并集（快速判断"是不是我的设备"）。 */
    public Set<String> allSigs() {
        Set<String> out = new LinkedHashSet<>();
        for (Comp c : comps) out.addAll(c.sigs);
        return out;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (Comp c : comps) {
                JSONObject j = new JSONObject();
                j.put("addr", c.addr);
                j.put("role", c.role);
                JSONArray s = new JSONArray();
                for (String x : c.sigs) s.put(x);
                j.put("sigs", s);
                arr.put(j);
            }
            o.put("comps", arr);
            o.put("name", deviceName);
            o.put("tx", (double) txPower);
            o.put("noise", (double) noise);
            o.put("at", learnedAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Profile fromJson(String text) {
        Profile p = new Profile();
        if (text == null || text.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray arr = o.optJSONArray("comps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    Comp c = new Comp(j.optString("addr", ""), j.optString("role", ROLE_BUDS));
                    JSONArray s = j.optJSONArray("sigs");
                    if (s != null) for (int k = 0; k < s.length(); k++) c.sigs.add(s.getString(k));
                    if (!c.sigs.isEmpty()) p.comps.add(c);
                }
            }
            p.deviceName = o.optString("name", "");
            p.txPower = (float) o.optDouble("tx", -59);
            p.noise = (float) o.optDouble("noise", 3);
            p.learnedAt = o.optLong("at", 0);
        } catch (Exception ignored) {
        }
        return p;
    }

    /** 角色中文名。 */
    public static String roleName(String role) {
        if (ROLE_L.equals(role)) return "左耳";
        if (ROLE_R.equals(role)) return "右耳";
        if (ROLE_CASE.equals(role)) return "充电仓";
        return "耳机";
    }

    /**
     * 按丢失的部件拼提醒文案，覆盖：
     * 左耳 / 右耳 / 耳机仓 / 左耳+耳机仓 / 右耳+耳机仓 / 左耳+右耳 / 耳机（无法区分时）
     */
    public static String missingText(boolean l, boolean r, boolean c) {
        if (l && r && c) return "左耳+右耳+耳机仓遗落";
        if (l && r) return "左耳+右耳遗落";
        if (l && c) return "左耳+耳机仓遗落";
        if (r && c) return "右耳+耳机仓遗落";
        if (l) return "左耳遗落";
        if (r) return "右耳遗落";
        if (c) return "耳机仓遗落";
        return "耳机遗落";
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Comp c : comps) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(roleName(c.role));
        }
        return sb.toString();
    }
}
