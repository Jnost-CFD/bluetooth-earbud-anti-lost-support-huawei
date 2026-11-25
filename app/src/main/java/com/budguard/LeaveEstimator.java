package com.budguard;

/**
 * 遗落位置拟合。
 *
 * 思路：耳机本身不动，真正有信息量的是「你最后一次离它很近时手机在哪」。
 * 所以：
 *   1. 优先取「最后一次距离 ≤ 2 米」时刻的定位（near）——它最接近耳机实际落点；
 *   2. 判定遗落那一刻的定位（detected）是辅助——人已经走开几米，只用来在 near 缺失或很差时兜底；
 *   3. 两个都有效时按 1/acc² 加权融合，但 near 加权 ×2（先验更可信）。
 */
public final class LeaveEstimator {

    /** 距离小于该值（米）时的定位才算「贴近时刻」。 */
    public static final float NEAR_METERS = 2.0f;

    public static Loc fuse(Loc near, Loc detected) {
        boolean n = near != null && near.valid();
        boolean d = detected != null && detected.valid();
        if (!n && !d) return null;
        if (n && !d) return near;
        if (!n) return detected;

        double wN = 2.0 / Math.pow(Math.max(near.acc, 5f), 2);
        double wD = 1.0 / Math.pow(Math.max(detected.acc, 5f), 2);
        double sum = wN + wD;
        double lat = (near.lat * wN + detected.lat * wD) / sum;
        double lng = (near.lng * wN + detected.lng * wD) / sum;
        float acc = (float) Math.sqrt(1.0 / sum);
        return new Loc(lat, lng, acc, Math.max(near.at, detected.at), "fused");
    }

    public static int confidence(Loc fused, boolean hasNear) {
        if (fused == null) return 0;
        int c = 95;
        if (!hasNear) c -= 30;                       // 没有贴近样本，只能靠判定时刻
        if (fused.acc > 20) c -= 20;
        if (fused.acc > 50) c -= 25;
        if (fused.acc > 100) c -= 20;
        return Math.max(0, Math.min(100, c));
    }

    public static String confidenceText(int c) {
        if (c >= 80) return "高";
        if (c >= 55) return "中";
        if (c > 0) return "低";
        return "无";
    }
}
