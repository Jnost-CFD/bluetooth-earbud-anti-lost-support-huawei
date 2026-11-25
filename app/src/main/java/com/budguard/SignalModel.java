package com.budguard;

/**
 * 信号模型：把 RSSI 变成「距离 + 置信度」。
 *
 * 设计要点：
 *  1. 中位数滤波（5 窗）——比 EMA 抗突发尖峰，人转身遮挡时不会乱跳。
 *  2. 在线噪声估计——跟踪中位数相邻两次的绝对差，得到这台设备在你环境里的真实抖动 σ。
 *  3. 距离用对数路径损耗模型 d = 10^((A - RSSI)/(10n))，A 在学习时按 0.4m 自动标定。
 *  4. 门限判定带 2σ 裕量——只有「明显超过」设定距离才认为遗落，抑制噪声误报。
 */
public final class SignalModel {

    private static final float PATH_LOSS_N = 2.0f;
    private static final int WIN = 5;

    private final double[] win = new double[WIN];
    private int count;
    private int idx;

    private double median = Double.NaN;
    private double prevMedian = Double.NaN;
    private double noiseEwma = 3.0;
    private long lastAt;

    public void add(int rssi) {
        win[idx % WIN] = rssi;
        idx++;
        if (count < WIN) count++;
        prevMedian = median;
        median = medianOfWindow();
        lastAt = System.currentTimeMillis();

        if (!Double.isNaN(prevMedian) && !Double.isNaN(median)) {
            double step = Math.abs(median - prevMedian);
            noiseEwma = noiseEwma * 0.8 + step * 0.2;
        }
    }

    private double medianOfWindow() {
        int n = Math.min(count, WIN);
        if (n == 0) return Double.NaN;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = win[i];
        java.util.Arrays.sort(a);
        return (n % 2 == 1) ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2.0;
    }

    public boolean hasData() {
        return count > 0;
    }

    public double rssi() {
        return median;
    }

    /** 这台设备的实测抖动（dB），下限 1.5 防止过度自信。 */
    public double noise() {
        return Math.max(1.5, Math.min(12.0, noiseEwma));
    }

    /** 正数表示比上一次更近。 */
    public double trendDb() {
        if (Double.isNaN(prevMedian) || Double.isNaN(median)) return 0;
        return median - prevMedian;
    }

    public double meters(float txPower) {
        if (Double.isNaN(median)) return 50;
        double d = Math.pow(10.0, (txPower - median) / (10.0 * PATH_LOSS_N));
        return Math.min(50.0, Math.max(0.15, d));
    }

    /**
     * 是否已明显超出 meters：RSSI 低于理论门限再留 2σ 裕量。
     * 例如设定 5m、σ=3dB，则需要低于门限 6dB 才判超距。
     */
    /**
     * 是否已明显超出 meters。
     * marginSigma 是留多少倍 σ 的裕量：档位越近、越要卡紧（避免"形影不离"虚高）。
     */
    public boolean beyond(double meters, float txPower, double marginSigma) {
        if (Double.isNaN(median) || meters <= 0) return false;
        double limit = txPower - 10.0 * PATH_LOSS_N * Math.log10(meters) - marginSigma * noise();
        return median < limit;
    }

    public void reset() {
        count = 0;
        idx = 0;
        median = Double.NaN;
        prevMedian = Double.NaN;
        noiseEwma = 3.0;
        lastAt = 0;
    }

    public long lastSampleAt() {
        return lastAt;
    }
}
