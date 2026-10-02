package com.oscar.wifiradar;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

/** 本机网络信息：IP / 网关 / DNS / 当前连接详情 */
public class NetInfo {

    /** 本机 IPv4 地址（含前缀长度），如 192.168.1.5/24 */
    public static String localIpv4(Context ctx) {
        LinkProperties lp = linkProps(ctx);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return a.getHostAddress() + "/" + la.getPrefixLength();
                }
            }
        }
        return "未连接/无 IPv4";
    }

    public static String gateway(Context ctx) {
        LinkProperties lp = linkProps(ctx);
        if (lp != null) {
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null) {
                    return r.getGateway().getHostAddress();
                }
            }
        }
        return "--";
    }

    public static String dns(Context ctx) {
        LinkProperties lp = linkProps(ctx);
        if (lp != null) {
            List<InetAddress> dns = lp.getDnsServers();
            if (dns != null && !dns.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < dns.size() && i < 3; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(dns.get(i).getHostAddress());
                }
                return sb.toString();
            }
        }
        return "--";
    }

    public static String mac(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "--";
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return "--";
        String mac = info.getMacAddress();
        // Android 6+ 对非连接状态返回固定值
        if (mac == null || "02:00:00:00:00:00".equals(mac)) return "不可读 (Android 限制)";
        return mac;
    }

    private static LinkProperties linkProps(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network net = cm.getActiveNetwork();
        if (net == null) return null;
        return cm.getLinkProperties(net);
    }

    /** 当前连接的 SSID（去引号） */
    public static String connectedSsid(WifiManager wm) {
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return "--";
        String ssid = info.getSSID();
        if (ssid == null) return "--";
        ssid = ssid.trim();
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid.isEmpty() ? "--" : ssid;
    }

    /** 当前连接速率 Mbps（-1 表示未知） */
    public static int linkSpeedMbps(WifiManager wm) {
        WifiInfo info = wm.getConnectionInfo();
        return info == null ? -1 : info.getLinkSpeed();
    }

    /** 当前连接信号 RSSI（-127 表示未知） */
    public static int rssi(WifiManager wm) {
        WifiInfo info = wm.getConnectionInfo();
        return info == null ? -127 : info.getRssi();
    }

    /** 当前连接 BSSID */
    public static String connectedBssid(WifiManager wm) {
        WifiInfo info = wm.getConnectionInfo();
        return info == null || info.getBSSID() == null ? "--" : info.getBSSID();
    }
}
