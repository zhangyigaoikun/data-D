package com.oscar.wifiradar;

import android.net.wifi.ScanResult;

/** 单个 WiFi 热点（AP）的数据模型与解析 */
public class ScanResultItem {

    public final String ssid;
    public final String bssid;
    public final int level;      // dBm, 负数
    public final int frequency;  // MHz
    public final int channel;
    public final String band;    // "2.4GHz" / "5GHz" / "6GHz"
    public final String standard;// WiFi 4/5/6/6E/7 或 802.11a/b/g
    public final String security;// 加密方式
    public final boolean encrypted;
    public final String capabilities;
    public final long firstSeen;

    public ScanResultItem(ScanResult r) {
        this.ssid = (r.SSID == null || r.SSID.isEmpty()) ? "(隐藏网络)" : r.SSID;
        this.bssid = r.BSSID == null ? "--" : r.BSSID;
        this.level = r.level;
        this.frequency = r.frequency;
        this.channel = channelOf(r.frequency);
        this.band = bandOf(r.frequency);
        this.standard = guessStandard(r);
        this.security = securityOf(r);
        this.encrypted = isEncrypted(r);
        this.capabilities = r.capabilities == null ? "" : r.capabilities;
        this.firstSeen = System.currentTimeMillis();
    }

    /** 频段 -> 信道号 */
    public static int channelOf(int freq) {
        if (freq >= 2412 && freq <= 2484) return (freq - 2407) / 5;
        if (freq >= 4900 && freq < 5925) return (freq - 5000) / 5;
        if (freq >= 5925 && freq <= 7125) return (freq - 5950) / 5; // 6GHz 信道 1/5/9...
        return 0;
    }

    public static String bandOf(int freq) {
        if (freq >= 2400 && freq < 2500) return "2.4GHz";
        if (freq >= 4900 && freq < 5925) return "5GHz";
        if (freq >= 5925) return "6GHz";
        return "?";
    }

    /** WifiInfo.IEEE80211_STANDARD_* 常量值（AOSP 固定值，官方文档定义） */
    private static final int STD_LEGACY = 0;
    private static final int STD_11A = 1;
    private static final int STD_11B = 2;
    private static final int STD_11G = 3;
    private static final int STD_11N = 4;
    private static final int STD_11AC = 5;
    private static final int STD_11AX = 6;
    private static final int STD_11BE = 7;

    /** 判断技术标准：优先官方字段（Android 10+），回退 capabilities 解析 */
    public static String guessStandard(ScanResult r) {
        String cap = r.capabilities == null ? "" : r.capabilities;
        int freq = r.frequency;
        boolean sixG = freq >= 5925;

        // API 30+ 官方标准字段（比 capabilities 解析准确）
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            int std = r.getWifiStandard();
            switch (std) {
                case STD_11BE:
                    return "WiFi 7 (802.11be)";
                case STD_11AX:
                    return sixG ? "WiFi 6E (802.11ax)" : "WiFi 6 (802.11ax)";
                case STD_11AC:
                    return "WiFi 5 (802.11ac)";
                case STD_11N:
                    return "WiFi 4 (802.11n)";
                case STD_11G:
                    return "802.11g";
                case STD_11A:
                    return "802.11a";
                case STD_11B:
                    return "802.11b";
                default:
                    break; // LEGACY/未知 -> 回退
            }
        }

        if (cap.contains("[EHT")) return "WiFi 7 (802.11be)";
        if (cap.contains("[HE")) return sixG ? "WiFi 6E (802.11ax)" : "WiFi 6 (802.11ax)";
        if (cap.contains("[VHT")) return "WiFi 5 (802.11ac)";
        if (cap.contains("[HT")) return "WiFi 4 (802.11n)";
        // 官方字段与 capabilities 均无信息：不臆断为 802.11a/b/g，诚实标注未知
        if (freq >= 4900 && freq < 5925) return "5GHz 未知";
        if (sixG) return "6GHz 未知";
        return "2.4GHz 未知";
    }

    public static boolean isEncrypted(ScanResult r) {
        String cap = r.capabilities == null ? "" : r.capabilities;
        // SAE = WPA3 的认证协议（部分固件只上报 SAE 而非 WPA3 字样）；PSK/EAP 兜底
        return cap.contains("WPA") || cap.contains("WEP") || cap.contains("OWE")
                || cap.contains("SAE") || cap.contains("PSK") || cap.contains("EAP");
    }

    public static String securityOf(ScanResult r) {
        String cap = r.capabilities == null ? "" : r.capabilities;
        if (cap.contains("WPA3") || cap.contains("SAE")) return "WPA3";
        if (cap.contains("OWE")) return "OWE";
        if (cap.contains("WPA2")) return "WPA2";
        if (cap.contains("WPA")) return "WPA";
        if (cap.contains("WEP")) return "WEP";
        if (cap.contains("EAP")) return "企业";
        return cap.contains("ESS") ? "开放" : "开放";
    }

    /** 信号质量分级: 0 极弱 ~ 4 极强 */
    public static int signalLevel(int dbm) {
        if (dbm >= -50) return 4;
        if (dbm >= -60) return 3;
        if (dbm >= -70) return 2;
        if (dbm >= -80) return 1;
        return 0;
    }

    public static String signalText(int dbm) {
        int lv = signalLevel(dbm);
        switch (lv) {
            case 4: return "极强";
            case 3: return "良好";
            case 2: return "一般";
            case 1: return "较弱";
            default: return "极弱";
        }
    }

    /** 信号强度 -> 颜色 */
    public static int signalColor(int dbm) {
        switch (signalLevel(dbm)) {
            case 4: return 0xFF4CAF50; // 绿
            case 3: return 0xFF8BC34A;
            case 2: return 0xFFFFC107; // 黄
            case 1: return 0xFFFF9800;
            default: return 0xFFF44336; // 红
        }
    }
}
