package com.budguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自动识别「哪一堆广播属于我的耳机」。
 *
 * 判据（按实测日志里的真实噪声设计）：
 *  - 名字里出现 FreeBuds / FreeClip / HUAWEI / HONOR  → 强正分，基本一锤定音
 *  - 出现华为服务数据 sd:fdee:*                      → 正分（华为耳机专属通道）
 *  - 信号越强越可能是你手里那台（学习时你就在旁边）
 *  - Apple 厂商数据 mfg:76:*（0x004C）              → 强负分，那是别人的 iPhone/AirPods
 *  - 名字像车钥匙/门锁/电脑（Zeekr、Geely、NIO、LOCK_、WIN-…）→ 强负分
 * 得分最高、且不显著低于最高分的地址组会被一并认领（左右耳常是两个地址）。
 */
public final class LearnEngine {

    public static final class Cand {
        public final String addr;
        public final Set<String> sigs = new LinkedHashSet<>();
        public final Set<String> names = new LinkedHashSet<>();
        public int count;
        public int best = -127;
        public int score;
        final List<Integer> hist = new ArrayList<>();

        Cand(String addr) {
            this.addr = addr;
        }

        public String name() {
            for (String n : names) if (n != null && !n.isEmpty()) return n;
            return "";
        }

        /** 学习期间的 RSSI 中位数：比 best 稳健，best 往往是偶发尖峰。 */
        public int medianRssi() {
            if (hist.isEmpty()) return best;
            List<Integer> copy = new ArrayList<>(hist);
            Collections.sort(copy);
            return copy.get(copy.size() / 2);
        }
    }

    private final Map<String, Cand> byAddr = new LinkedHashMap<>();
    private long startedAt = System.currentTimeMillis();

    public void reset() {
        byAddr.clear();
        startedAt = System.currentTimeMillis();
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public int addressCount() {
        return byAddr.size();
    }

    public void onBeacon(Beacon b) {
        if (b.addr == null || b.addr.isEmpty()) return;
        Cand c = byAddr.get(b.addr);
        if (c == null) {
            c = new Cand(b.addr);
            byAddr.put(b.addr, c);
        }
        c.count++;
        if (b.rssi > c.best) c.best = b.rssi;
        if (c.hist.size() < 31) c.hist.add(b.rssi);
        if (b.name != null && !b.name.isEmpty()) c.names.add(b.name);
        c.sigs.addAll(b.sigs);
    }

    private void scoreAll() {
        for (Cand c : byAddr.values()) {
            int s = 0;
            String name = c.name();
            String lower = name.toLowerCase(Locale.US);

            if (lower.contains("freebuds") || lower.contains("freeclip")
                    || lower.contains("huawei") || lower.contains("honor")) {
                s += 100;
            }
            boolean hasFdee = false;
            boolean hasApple = false;
            for (String sig : c.sigs) {
                if (sig.startsWith("sd:fdee")) hasFdee = true;
                if (sig.startsWith("mfg:76:")) hasApple = true;
            }
            if (hasFdee) s += 60;
            if (hasApple) s -= 200;

            if (lower.contains("zeekr") || lower.contains("geely") || lower.contains("nio")
                    || lower.contains("lock_") || lower.contains("win-")
                    || lower.contains("car") || lower.contains("key")) {
                s -= 120;
            }

            // 越近越可能是你正在学的那台（学习时耳机就在手边）
            if (c.best >= -45) s += 30;
            else if (c.best >= -55) s += 18;
            else if (c.best >= -65) s += 8;
            else s -= 10;

            if (c.count >= 20) s += 10;
            if (c.count <= 2) s -= 15;      // 偶然扫到的路人设备

            c.score = s;
        }
    }

    /** 默认角色猜测：带产品名的通常是充电仓（仓里有独立 BLE 芯片负责离线查找）；其余按顺序给左右耳。 */
    private static String suggestRole(Cand c, int idx) {
        String lower = c.name().toLowerCase(Locale.US);
        if (lower.contains("freebuds") || lower.contains("freeclip")) {
            return Profile.ROLE_CASE;
        }
        if (idx == 0) return Profile.ROLE_L;
        if (idx == 1) return Profile.ROLE_R;
        return Profile.ROLE_BUDS;
    }

    /** 选出属于我的地址组，按「部件」组织成 Profile。没有任何可信候选时返回空 Profile。 */
    public Profile build() {
        scoreAll();
        List<Cand> list = new ArrayList<>(byAddr.values());
        if (list.isEmpty()) return new Profile();

        Collections.sort(list, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                if (b.score != a.score) return Integer.compare(b.score, a.score);
                if (b.best != a.best) return Integer.compare(b.best, a.best);
                return Integer.compare(b.count, a.count);
            }
        });

        Cand top = list.get(0);
        if (top.score < 40) return new Profile();   // 没有足够像耳机的目标

        Profile p = new Profile();
        // 名字直接命中华为耳机 —— 无条件认领；
        // 其余（另一只耳机 / 另一帧型）—— 必须在信号强度上属于「就在手边」那一档。
        int rssiWindow = top.best - 15;
        int bestMedian = -127;
        int idx = 0;

        for (Cand c : list) {
            String lower = c.name().toLowerCase(Locale.US);
            boolean nameMatch = lower.contains("freebuds") || lower.contains("freeclip")
                    || lower.contains("huawei") || lower.contains("honor");
            if (!nameMatch) {
                if (c.score < 40) continue;
                if (c.best < rssiWindow) continue;
            }

            Profile.Comp comp = new Profile.Comp(c.addr, suggestRole(c, idx));
            for (String sig : c.sigs) {
                if (sig.startsWith("mfg:76:")) continue;      // Apple 一律不要
                comp.sigs.add(sig);
            }
            if (comp.sigs.isEmpty()) continue;
            p.comps.add(comp);
            idx++;
            if (c.medianRssi() > bestMedian) bestMedian = c.medianRssi();
        }

        if (p.comps.isEmpty()) return new Profile();

        // 标定：学习时按「充电盒距手机约 0.3m」计。
        // r = A - 10n*log10(d)  =>  A = r + 10n*log10(d0)，d0=0.3、n=2  =>  A = r - 10.5
        p.txPower = bestMedian - 10.5f;
        p.noise = 3.0f;
        p.learnedAt = System.currentTimeMillis();
        return p;
    }

    /** 给界面看的候选列表（已排序、带分数）。 */
    public List<Cand> ranked() {
        scoreAll();
        List<Cand> list = new ArrayList<>(byAddr.values());
        Collections.sort(list, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                if (b.score != a.score) return Integer.compare(b.score, a.score);
                return Integer.compare(b.best, a.best);
            }
        });
        return list;
    }
}
