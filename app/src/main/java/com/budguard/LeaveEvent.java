package com.budguard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 一次遗落记录：结果文案 + 各部件「最后听到」信息 + 推定落点。 */
public final class LeaveEvent {

    /** 一个缺失部件：最后一次听到的时间 / 当时距离 / 当时的手机定位。 */
    public static final class Part {
        public String role = Profile.ROLE_BUDS;
        public long lastHeardAt;
        /** 判定遗落时的距离（米）；-1 表示该部件是静默丢失、测不到距离。 */
        public float meters = -1f;
        public Loc loc;

        public String roleName() {
            return Profile.roleName(role);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("role", role);
                o.put("t", lastHeardAt);
                o.put("m", (double) meters);
                if (loc != null) o.put("loc", loc.toJson());
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Part fromJson(JSONObject o) {
            Part p = new Part();
            p.role = o.optString("role", Profile.ROLE_BUDS);
            p.lastHeardAt = o.optLong("t", 0);
            p.meters = (float) o.optDouble("m", -1);
            p.loc = Loc.fromJson(o.optJSONObject("loc"));
            return p;
        }

        public String distanceText() {
            if (meters < 0) return "信号已断开";
            return meters < 10 ? String.format(Locale.US, "约 %.1f 米", meters)
                    : "约 " + Math.round(meters) + " 米";
        }

        public String heardText() {
            if (lastHeardAt <= 0) return "未记录";
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(lastHeardAt));
        }
    }

    /** 判定遗落的时刻。 */
    public long at;
    /** "左耳+耳机仓遗落" 这类结果文案。 */
    public String missingText = "";
    /** 每个缺失部件各自的最后听到信息。 */
    public final List<Part> parts = new ArrayList<>();
    /** 推定落点：各位置按可信度加权融合，用于找耳机。 */
    public Loc estimate;
    /** 判定时刻的手机定位。 */
    public Loc detected;
    /** 反向地理编码的中文地址（基于推定落点）。 */
    public String address = "";
    /** 0..100，越高越可信。 */
    public int confidence;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("at", at);
            o.put("txt", missingText == null ? "" : missingText);
            JSONArray ps = new JSONArray();
            for (Part p : parts) ps.put(p.toJson());
            o.put("parts", ps);
            if (estimate != null) o.put("est", estimate.toJson());
            if (detected != null) o.put("det", detected.toJson());
            o.put("addr", address == null ? "" : address);
            o.put("conf", confidence);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static LeaveEvent fromJson(JSONObject o) {
        LeaveEvent e = new LeaveEvent();
        e.at = o.optLong("at", 0);
        e.missingText = o.optString("txt", "");
        JSONArray ps = o.optJSONArray("parts");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                e.parts.add(Part.fromJson(ps.optJSONObject(i)));
            }
        }
        e.estimate = Loc.fromJson(o.optJSONObject("est"));
        e.detected = Loc.fromJson(o.optJSONObject("det"));
        e.address = o.optString("addr", "");
        e.confidence = o.optInt("conf", 0);
        return e;
    }

    public String timeText() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(at));
    }

    public String resultText() {
        return missingText == null || missingText.isEmpty() ? "耳机遗落" : missingText;
    }

    public String placeText() {
        if (address != null && !address.isEmpty()) return address;
        Loc l = best();
        return l == null ? "无位置记录" : l.coord();
    }

    public Loc best() {
        return estimate != null ? estimate : detected;
    }
}
