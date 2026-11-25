package com.budguard;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条 BLE 广播的解析结果。
 *
 * 关键教训（来自 FreeBuds Pro 3 实测日志）：
 * 耳机用的是 Service Data (AD type 0x16, UUID 0xFDEe)，充电盒用的是 Manufacturer Data
 * (company 0x0901, 首字节 0x20 + 20 字节轮换密钥)。两者都不出现在 getServiceUuids() 里，
 * 所以必须自己遍历 AD 结构，否则所有这类设备会被压成同一个空指纹。
 *
 * 指纹只保留「稳定部分」：厂商ID / UUID / 类型字节 / 数据长度。
 * 轮换密钥、随机 MAC、RSSI 一律不进指纹，否则第二次就匹配不上。
 */
public final class Beacon {

    public final String addr;
    public final String name;
    public final int rssi;
    /** 完整广播记录 hex（调试用）。 */
    public final String recordHex;
    /** 这条广播里所有 AD 元素归一化后的指纹（去重、保序）。 */
    public final Set<String> sigs;
    /** 与 sigs 对应的可读描述，例如 "SD:fdee/0x05/8B"。 */
    public final Set<String> notes;

    private Beacon(String addr, String name, int rssi, String recordHex,
                   Set<String> sigs, Set<String> notes) {
        this.addr = addr;
        this.name = name;
        this.rssi = rssi;
        this.recordHex = recordHex;
        this.sigs = sigs;
        this.notes = notes;
    }

    public static Beacon from(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        String addr = "";
        try {
            addr = r.getDevice().getAddress();
        } catch (SecurityException ignored) {
        }
        String name = rec == null ? null : rec.getDeviceName();
        String hex = "";
        Set<String> sigs = new LinkedHashSet<>();
        Set<String> notes = new LinkedHashSet<>();

        if (rec != null) {
            byte[] raw = null;
            try {
                raw = rec.getBytes();
            } catch (Exception ignored) {
            }
            if (raw != null) {
                hex = toHex(raw, 62);
                parseAd(raw, sigs, notes);
            }
            // 厂商数据以 SparseArray 的 key 为准（key 就是解析好的厂商 ID，不会读错大小端）
            parseMfg(rec, sigs, notes);
            // 兜底：什么都没解析出来时用服务 UUID 列表
            if (sigs.isEmpty()) {
                try {
                    List<android.os.ParcelUuid> uuids = rec.getServiceUuids();
                    if (uuids != null) {
                        for (android.os.ParcelUuid u : uuids) {
                            String s = "svc:" + u.getUuid().toString().toLowerCase(Locale.US);
                            sigs.add(s);
                            notes.add("SVC:" + u.getUuid());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return new Beacon(addr, name == null ? "" : name, r.getRssi(), hex, sigs, notes);
    }

    private static void parseMfg(ScanRecord rec, Set<String> sigs, Set<String> notes) {
        android.util.SparseArray<byte[]> msd;
        try {
            msd = rec.getManufacturerSpecificData();
        } catch (Exception e) {
            return;
        }
        if (msd == null) return;
        for (int k = 0; k < msd.size(); k++) {
            int company = msd.keyAt(k) & 0xFFFF;
            byte[] d = msd.valueAt(k);
            if (d == null) continue;
            // 有些实现把厂商 ID 也放在值里，检测到就跳过，兼容两种行为
            int off = 0;
            if (d.length >= 2 && (((d[0] & 0xFF) | ((d[1] & 0xFF) << 8))) == company) off = 2;
            int kind = d.length > off ? (d[off] & 0xFF) : -1;
            int payload = d.length - off;
            sigs.add(String.format(Locale.US, "mfg:%d:%02x:%d", company, kind, payload));
            notes.add(String.format(Locale.US, "MFG:0x%04x/0x%02x/%dB", company, kind, payload));
            // 通配同结构（忽略类型字节）：仓盖开合可能导致类型字节变化
            sigs.add(String.format(Locale.US, "mfg:%d:*:%d", company, payload));
        }
    }

    private static void parseAd(byte[] raw, Set<String> sigs, Set<String> notes) {
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0) break;                       // 结束
            if (i + 1 + len > raw.length) break;       // 越界，丢弃残包
            int type = raw[i + 1] & 0xFF;
            int dataOff = i + 2;
            int dataLen = len - 1;                     // 类型字节之后的数据长度

            if (type == 0x16 && dataLen >= 2) {        // Service Data - 16 bit UUID
                int uuid = (raw[dataOff] & 0xFF) | ((raw[dataOff + 1] & 0xFF) << 8);
                int kind = dataLen > 2 ? (raw[dataOff + 2] & 0xFF) : -1;
                sigs.add(String.format(Locale.US, "sd:%04x:%02x:%d", uuid, kind, dataLen - 2));
                notes.add(String.format(Locale.US, "SD:%04x/0x%02x/%dB", uuid, kind, dataLen - 2));
            } else if (type == 0x20 && dataLen >= 4) { // Service Data - 32 bit UUID
                long uuid = ((raw[dataOff] & 0xFFL)) | ((raw[dataOff + 1] & 0xFFL) << 8)
                        | ((raw[dataOff + 2] & 0xFFL) << 16) | ((raw[dataOff + 3] & 0xFFL) << 24);
                int kind = dataLen > 4 ? (raw[dataOff + 4] & 0xFF) : -1;
                sigs.add(String.format(Locale.US, "sd32:%08x:%02x:%d", uuid, kind, dataLen - 4));
                notes.add(String.format(Locale.US, "SD32:%08x/0x%02x/%dB", uuid, kind, dataLen - 4));
            } else if (type == 0x21 && dataLen >= 16) { // Service Data - 128 bit UUID
                sigs.add("sd128:" + toHex(sub(raw, dataOff, 16), 16));
                notes.add("SD128:" + toHex(sub(raw, dataOff, 16), 4));
            } else if (type == 0xFF) {
                // 厂商数据统一由 parseMfg 用 SparseArray 的 key 处理，这里跳过，避免重复与误读
            } else if (type == 0x02 || type == 0x03) {  // 服务 UUID 列表
                for (int p = dataOff; p + 1 < dataOff + dataLen; p += 2) {
                    int uuid = (raw[p] & 0xFF) | ((raw[p + 1] & 0xFF) << 8);
                    sigs.add(String.format(Locale.US, "svc:%04x", uuid));
                    notes.add(String.format(Locale.US, "SVC:%04x", uuid));
                }
            }
            i += len + 1;
        }
    }

    private static byte[] sub(byte[] a, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    private static String toHex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", b[i]));
        }
        return sb.toString();
    }

    public String label() {
        String n = name == null || name.isEmpty() ? "" : name;
        return n.isEmpty() ? ("未知设备 " + addr) : (n + " (" + addr + ")");
    }

    public List<String> noteList() {
        return new ArrayList<>(notes);
    }
}
