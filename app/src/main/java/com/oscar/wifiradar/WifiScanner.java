package com.oscar.wifiradar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 封装 WiFi 扫描（startScan + 结果广播） */
public class WifiScanner {

    public interface Listener {
        void onScanResults(List<ScanResultItem> results, boolean updated);
    }

    private final Context ctx;
    private final WifiManager wm;
    private final BroadcastReceiver receiver;
    private Listener listener;
    private boolean registered = false;

    public WifiScanner(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        this.receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    boolean ok = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                    publish(ok);
                }
            }
        };
    }

    public void start() {
        if (!registered) {
            ctx.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            registered = true;
        }
    }

    public void stop() {
        if (registered) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
    }

    /** 触发一次扫描；系统通常每 30 秒内只允许数次，失败返回 false */
    public boolean requestScan() {
        return wm != null && wm.isWifiEnabled() && wm.startScan();
    }

    public boolean isWifiOn() {
        return wm != null && wm.isWifiEnabled();
    }

    public WifiManager getWifiManager() {
        return wm;
    }

    private void publish(boolean ok) {
        if (wm == null) return;
        List<ScanResultItem> items = new ArrayList<>();
        List<android.net.wifi.ScanResult> raw = wm.getScanResults();
        for (android.net.wifi.ScanResult r : raw) {
            items.add(new ScanResultItem(r));
        }
        Collections.sort(items, new Comparator<ScanResultItem>() {
            @Override
            public int compare(ScanResultItem a, ScanResultItem b) {
                return Integer.compare(b.level, a.level); // 信号从强到弱
            }
        });
        if (listener != null) listener.onScanResults(items, ok);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }
}
