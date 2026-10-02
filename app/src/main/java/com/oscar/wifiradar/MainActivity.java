package com.oscar.wifiradar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final long AUTO_SCAN_MS = 3000;
    private static final int HISTORY_CAP = 60;

    private WifiScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoScan = true;
    private boolean hasPermission = false;

    // 视图
    private TextView tvTitle, tvConn, tvEmpty;
    private TextView chipSignal, chipName, chipChannel;
    private TextView chipAll, chip2g, chip5g, chip5gLow, chip5gMid, chip5gHigh, chip6g;
    private View subBandChips;
    private RecyclerView rvList;
    private ViewPager2 channelPager;
    private ChannelPageAdapter channelAdapter;
    private FloorPlanView floorView;
    private View panelList, panelChannel, panelInfo, panelFloor;
    private LinearLayout infoContainer;
    private TextView tvFloorStatus;
    private View tabList, tabChannel, tabInfo, tabFloor;
    private TextView tvAuto;

    private ApAdapter adapter;
    private List<ScanResultItem> lastResults = new ArrayList<>();
    private int sortMode = 0; // 0 信号 1 名称 2 信道
    private int bandFilter = ChannelChartView.FILTER_ALL;

    // 动画（跟随系统动画速率）
    private boolean chipInit = false;
    private final Map<TextView, ValueAnimator> chipAnims = new HashMap<>();
    private ValueAnimator pagerAnim = null;

    // 信号历史：bssid -> 历史 dBm
    private final Map<String, ArrayDeque<Float>> historyMap = new HashMap<>();

    // 详情弹窗（与设置同款居中卡片弹窗，✕/完成/系统返回键均可关闭）
    private Dialog detailDialog = null;
    private String detailBssid = null;

    // 采样暂停：手指按下图表后 3 秒内不扫描、不刷新图表（防止切换/浏览时柱子乱跳）
    private long pauseChartUntil = 0;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fine = Boolean.TRUE.equals(result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false));
                boolean coarse = Boolean.TRUE.equals(result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false));
                hasPermission = fine || coarse;
                if (hasPermission) {
                    startScanning();
                } else {
                    Toast.makeText(this, "没有定位权限将无法扫描 WiFi（安卓系统硬性要求，App 不使用 GPS）", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) loadFloorImage(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupTabs();
        setupChips();
        setupChannelPager();
        applyLongPressSetting();
        applyFineSwipeSetting();
        setupFloor();

        // 系统返回键：详情/设置弹窗自带返回键关闭，这里仅处理正常返回
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        adapter = new ApAdapter(item -> showApDetail(item, true)); // 列表页：详情从底部伸出
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.setAdapter(adapter);

        int fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true;
            startScanning();
        } else {
            permLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvConn = findViewById(R.id.tv_conn);
        tvEmpty = findViewById(R.id.tv_empty);
        tvAuto = findViewById(R.id.btn_auto);
        rvList = findViewById(R.id.rv_list);
        channelPager = findViewById(R.id.channel_pager);
        floorView = findViewById(R.id.floor_view);
        panelList = findViewById(R.id.panel_list);
        panelChannel = findViewById(R.id.panel_channel);
        panelInfo = findViewById(R.id.panel_info);
        panelFloor = findViewById(R.id.panel_floor);
        infoContainer = findViewById(R.id.info_container);
        tvFloorStatus = findViewById(R.id.tv_floor_status);
        tabList = findViewById(R.id.tab_list);
        tabChannel = findViewById(R.id.tab_channel);
        tabInfo = findViewById(R.id.tab_info);
        tabFloor = findViewById(R.id.tab_floor);
        chipSignal = findViewById(R.id.chip_signal);
        chipName = findViewById(R.id.chip_name);
        chipChannel = findViewById(R.id.chip_channel);
        chipAll = findViewById(R.id.chip_all);
        chip2g = findViewById(R.id.chip_2g);
        chip5g = findViewById(R.id.chip_5g);
        chip5gLow = findViewById(R.id.chip_5g_low);
        chip5gMid = findViewById(R.id.chip_5g_mid);
        chip5gHigh = findViewById(R.id.chip_5g_high);
        chip6g = findViewById(R.id.chip_6g);
        subBandChips = findViewById(R.id.sub_band_chips);
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());

        // 详情弹窗（Dialog 形式，在 showApDetail 中创建）

        // 标题带版本号，方便区分每次安装的版本
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvTitle.setText("WiFi 雷达 v" + v);
        } catch (Exception ignored) {
        }
    }

    private void startScanning() {
        scanner = new WifiScanner(this);
        scanner.setListener(this::onScanResults);
        scanner.start();
        handler.post(autoScanRunnable);
        scanner.requestScan();
    }

    // ---------- 自动扫描 ----------
    private final Runnable autoScanRunnable = new Runnable() {
        @Override
        public void run() {
            // 用户正在图表上滑动/拖拽时暂停采样，避免刷新打断选中
            if (scanner != null && autoScan && System.currentTimeMillis() >= pauseChartUntil) {
                scanner.requestScan();
            }
            handler.postDelayed(this, AUTO_SCAN_MS);
        }
    };

    private void onScanResults(List<ScanResultItem> results, boolean updated) {
        lastResults = results;
        // 排序
        List<ScanResultItem> sorted = new ArrayList<>(results);
        switch (sortMode) {
            case 1:
                Collections.sort(sorted, Comparator.comparing(a -> a.ssid.toLowerCase(Locale.ROOT)));
                break;
            case 2:
                Collections.sort(sorted, Comparator.comparingInt(a -> a.channel));
                break;
            default:
                Collections.sort(sorted, (a, b) -> Integer.compare(b.level, a.level));
        }
        adapter.setItems(sorted);
        tvEmpty.setVisibility(sorted.isEmpty() ? View.VISIBLE : View.GONE);
        // 有选中热点时冻结图表（柱子/气泡不随扫描跳动），列表/本机照常更新；取消选中后恢复
        if (channelAdapter != null && System.currentTimeMillis() >= pauseChartUntil
                && !channelAdapter.hasSelection()) {
            channelAdapter.setData(results);
        }
        updateConnectionHeader();
        // 本机页只在可见时重建（避免每 3 秒无谓地 removeAllViews + addView 全部行）
        if (panelInfo.getVisibility() == View.VISIBLE) {
            updateInfoPanel();
        }
        updateHistory(results);
        refreshDetailSheet();
    }

    private void updateConnectionHeader() {
        if (scanner == null) return;
        WifiManager wm = scanner.getWifiManager() == null ? null : scanner.getWifiManager();
        if (wm == null) {
            tvConn.setText("WiFi 未开启或不可用");
            return;
        }
        String ssid = NetInfo.connectedSsid(wm);
        int speed = NetInfo.linkSpeedMbps(wm);
        int rssi = NetInfo.rssi(wm);
        String speedTxt = speed > 0 ? speed + " Mbps" : "--";
        tvConn.setText("已连接: " + ssid + "   ·   " + speedTxt + "   ·   " + rssi + " dBm");
    }

    private void updateInfoPanel() {
        infoContainer.removeAllViews();
        if (scanner == null) return;
        WifiManager wm = scanner.getWifiManager();
        if (wm == null) {
            addInfoRow("状态", "WiFi 不可用");
            return;
        }
        String ssid = NetInfo.connectedSsid(wm);
        int speed = NetInfo.linkSpeedMbps(wm);
        int rssi = NetInfo.rssi(wm);
        String bssid = NetInfo.connectedBssid(wm);

        addInfoRow("本机 IPv4", NetInfo.localIpv4(this));
        addInfoRow("网关", NetInfo.gateway(this));
        addInfoRow("DNS", NetInfo.dns(this));
        addInfoRow("MAC 地址", NetInfo.mac(this));
        addInfoRow("已连接 SSID", ssid);
        addInfoRow("连接 BSSID", bssid);
        addInfoRow("连接速率", speed > 0 ? speed + " Mbps" : "--");
        addInfoRow("当前信号", rssi + " dBm (" + ScanResultItem.signalText(rssi) + ")");

        // 当前连接的频段/标准（用 BSSID 匹配扫描结果）
        String curBand = "--", curStd = "--", curCh = "--";
        for (ScanResultItem it : lastResults) {
            if (it.bssid.equalsIgnoreCase(bssid)) {
                curBand = it.band;
                curStd = it.standard;
                curCh = "CH " + it.channel;
                break;
            }
        }
        // 扫描结果标准未知时，用协商速率精确推断（速率是硬件实报，2882 只有 be 能达到）
        if (curStd.contains("未知")) {
            if (speed >= 2402) curStd = "WiFi 7 (802.11be)";
            else if (speed >= 1201) curStd = "WiFi 6 (802.11ax)";
            else if (speed >= 867) curStd = "WiFi 5 (802.11ac)";
            else if (speed >= 300) curStd = "WiFi 4 (802.11n)";
            else curStd = "未知";
        }
        addInfoRow("连接频段", curBand + "  " + curCh);
        addInfoRow("连接技术标准", curStd);
        addInfoRow("扫描到热点数", lastResults.size() + " 个");
        addInfoRow("WiFi 开关", scanner.isWifiOn() ? "开启" : "关闭");
    }

    private void addInfoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView tvL = new TextView(this);
        tvL.setText(label);
        tvL.setTextColor(0xFF9AA0A6);
        tvL.setTextSize(14);
        row.addView(tvL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tvV = new TextView(this);
        tvV.setText(value);
        tvV.setTextColor(0xFFFFFFFF);
        tvV.setTextSize(14);
        tvV.setGravity(android.view.Gravity.END);
        row.addView(tvV, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        infoContainer.addView(row);
    }

    private void updateHistory(List<ScanResultItem> results) {
        for (ScanResultItem it : results) {
            ArrayDeque<Float> q = historyMap.get(it.bssid);
            if (q == null) {
                q = new ArrayDeque<>();
                historyMap.put(it.bssid, q);
            }
            if (q.size() >= HISTORY_CAP) q.pollFirst();
            q.addLast((float) it.level);
        }
        // 清理已消失的 AP
        java.util.Iterator<Map.Entry<String, ArrayDeque<Float>>> it = historyMap.entrySet().iterator();
        boolean gone;
        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<Float>> e = it.next();
            gone = true;
            for (ScanResultItem r : results) {
                if (r.bssid.equals(e.getKey())) { gone = false; break; }
            }
            if (gone) it.remove();
        }
    }

    // ---------- 采样暂停控制（图表手指按下暂停 / 抬起后窗口自然过期恢复） ----------
    private void onSamplingPaused() {
        pauseChartUntil = System.currentTimeMillis() + 3000;
    }

    private void onSamplingResumed() {
        // 保留 3 秒暂停窗口，防止刚松手就刷新打断浏览；窗口过期后自动恢复采样
    }

    // ---------- AP 详情（居中卡片弹窗，与设置同款；✕/完成/系统返回键均可关闭） ----------
    private void showApDetail(ScanResultItem item) {
        showApDetail(item, false);
    }

    private void showApDetail(ScanResultItem item, boolean fromBottom) {
        // 弹窗统一居中展示，fromBottom 仅保留签名兼容
        if (detailDialog != null && detailDialog.isShowing()) detailDialog.dismiss();
        detailDialog = new Dialog(this);
        detailDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        detailDialog.setContentView(R.layout.dialog_detail);
        if (detailDialog.getWindow() != null) {
            detailDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        detailBssid = item.bssid;

        TextView ssid = detailDialog.findViewById(R.id.sheet_ssid);
        TextView sub = detailDialog.findViewById(R.id.sheet_sub);
        TextView ch = detailDialog.findViewById(R.id.sheet_channel);
        TextView sig = detailDialog.findViewById(R.id.sheet_signal);
        TextView std = detailDialog.findViewById(R.id.sheet_standard);

        ssid.setText(item.ssid);
        sub.setText(item.bssid + " · " + (item.encrypted ? item.security : "开放网络"));
        ch.setText("信道 CH " + item.channel + "   ·   " + item.band);
        sig.setText("信号 " + item.level + " dBm（" + ScanResultItem.signalText(item.level) + "）");
        sig.setTextColor(ScanResultItem.signalColor(item.level));
        // 技术标准：若扫描字段未知但该 AP 是本机当前连接，用协商速率精确推断（速率是硬件实报，2882 只有 be 能达到）
        String stdTxt = item.standard;
        if (stdTxt.contains("未知") && scanner != null && scanner.getWifiManager() != null
                && item.bssid.equalsIgnoreCase(NetInfo.connectedBssid(scanner.getWifiManager()))) {
            int speed = NetInfo.linkSpeedMbps(scanner.getWifiManager());
            if (speed >= 2402) stdTxt = "WiFi 7 (802.11be)";
            else if (speed >= 1201) stdTxt = "WiFi 6 (802.11ax)";
            else if (speed >= 867) stdTxt = "WiFi 5 (802.11ac)";
            else if (speed >= 300) stdTxt = "WiFi 4 (802.11n)";
        }
        std.setText("技术标准: " + stdTxt);

        detailDialog.findViewById(R.id.btn_sheet_close).setOnClickListener(v -> detailDialog.dismiss());
        TextView collapse = detailDialog.findViewById(R.id.btn_sheet_collapse);
        collapse.setText("完成");
        collapse.setOnClickListener(v -> detailDialog.dismiss());
        detailDialog.setOnDismissListener(d -> {
            detailDialog = null;
            detailBssid = null;
        });
        refreshDetailSheet();
        detailDialog.show();
    }

    private void refreshDetailSheet() {
        if (detailDialog == null || !detailDialog.isShowing() || detailBssid == null) return;
        SignalChartView chart = detailDialog.findViewById(R.id.sheet_signal_chart);
        ArrayDeque<Float> q = historyMap.get(detailBssid);
        if (q != null) {
            chart.setData(new ArrayList<>(q));
        }
    }

    // ---------- Tab 与筛选 ----------
    private void setupTabs() {
        View.OnClickListener l = v -> {
            selectTab(v.getId());
        };
        tabList.setOnClickListener(l);
        tabChannel.setOnClickListener(l);
        tabInfo.setOnClickListener(l);
        tabFloor.setOnClickListener(l);
        selectTab(R.id.tab_list);
    }

    private void selectTab(int id) {
        setTabColor(tabList, id == R.id.tab_list, R.drawable.ic_list);
        setTabColor(tabChannel, id == R.id.tab_channel, R.drawable.ic_chart);
        setTabColor(tabInfo, id == R.id.tab_info, R.drawable.ic_info);
        setTabColor(tabFloor, id == R.id.tab_floor, R.drawable.ic_floor);

        panelList.setVisibility(id == R.id.tab_list ? View.VISIBLE : View.GONE);
        panelChannel.setVisibility(id == R.id.tab_channel ? View.VISIBLE : View.GONE);
        panelInfo.setVisibility(id == R.id.tab_info ? View.VISIBLE : View.GONE);
        panelFloor.setVisibility(id == R.id.tab_floor ? View.VISIBLE : View.GONE);
        // 切到本机页时立即用最新数据重建（平时隐藏不重建，节省开销）
        if (id == R.id.tab_info) updateInfoPanel();
    }

    private void setTabColor(View tab, boolean selected, int iconRes) {
        ImageView iv = tab.findViewById(R.id.tab_icon);
        TextView tv = tab.findViewById(R.id.tab_text);
        int color = selected ? 0xFF4FC3F7 : 0xFF9AA0A6;
        if (iv != null) {
            iv.setImageResource(iconRes);
            iv.setColorFilter(color);
        }
        if (tv != null) tv.setTextColor(color);
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_on : R.drawable.bg_tab_off);
    }

    private void setupChips() {
        View.OnClickListener sortL = v -> {
            sortMode = (int) v.getTag();
            updateSortChips();
            if (scanner != null) scanner.requestScan();
        };
        chipSignal.setTag(0);
        chipName.setTag(1);
        chipChannel.setTag(2);
        chipSignal.setOnClickListener(sortL);
        chipName.setOnClickListener(sortL);
        chipChannel.setOnClickListener(sortL);
        updateSortChips();

        View.OnClickListener bandL = v -> {
            if (v.getId() == R.id.chip_5g) {
                // 主行 5G：展开子菜单；若当前不在 5G 子页则默认低信道
                if (bandFilter < ChannelChartView.FILTER_5G_LOW || bandFilter > ChannelChartView.FILTER_5G_HIGH) {
                    bandFilter = ChannelChartView.FILTER_5G_LOW;
                }
            } else {
                bandFilter = (int) v.getTag();
            }
            updateBandChips();
            smoothSetChannelItem(bandFilter);
        };
        chipAll.setTag(ChannelChartView.FILTER_ALL);
        chip2g.setTag(ChannelChartView.FILTER_2G);
        chip5gLow.setTag(ChannelChartView.FILTER_5G_LOW);
        chip5gMid.setTag(ChannelChartView.FILTER_5G_MID);
        chip5gHigh.setTag(ChannelChartView.FILTER_5G_HIGH);
        chip6g.setTag(ChannelChartView.FILTER_6G);
        chipAll.setOnClickListener(bandL);
        chip2g.setOnClickListener(bandL);
        chip5g.setOnClickListener(bandL);
        chip5gLow.setOnClickListener(bandL);
        chip5gMid.setOnClickListener(bandL);
        chip5gHigh.setOnClickListener(bandL);
        chip6g.setOnClickListener(bandL);
        updateBandChips();
    }

    private void updateSortChips() {
        chipSignal.setBackgroundResource(sortMode == 0 ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        chipName.setBackgroundResource(sortMode == 1 ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        chipChannel.setBackgroundResource(sortMode == 2 ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
    }

    /** chip 选中态：颜色渐变过渡（ValueAnimator 自动跟随系统动画速率；从上次目标色渐变，避免闪） */
    private final Map<TextView, Boolean> chipStates = new HashMap<>();

    private void setChipState(TextView chip, boolean selected) {
        ValueAnimator old = chipAnims.remove(chip);
        if (old != null) old.cancel();

        // 渐变起点 = 上次目标状态的标准色（不是目标反色，避免全体闪蓝）
        Boolean prev = chipStates.get(chip);
        int curSolid = (prev == null || prev) ? 0x334FC3F7 : 0xFF1E2226;
        int curStroke = (prev == null || prev) ? 0xFF4FC3F7 : 0x004FC3F7;
        int toSolid = selected ? 0x334FC3F7 : 0xFF1E2226;
        int toStroke = selected ? 0xFF4FC3F7 : 0x004FC3F7;
        chipStates.put(chip, selected);

        final GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(18));
        // 已是目标色：直接定格，不播动画
        if (curSolid == toSolid && curStroke == toStroke) {
            gd.setColor(toSolid);
            gd.setStroke(dp(1), toStroke);
            chip.setBackground(gd);
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(180);
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            gd.setColor(blend(curSolid, toSolid, t));
            gd.setStroke(dp(1), blend(curStroke, toStroke, t));
            chip.setBackground(gd);
        });
        va.start();
        chipAnims.put(chip, va);
    }

    private int blend(int c1, int c2, float t) {
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }

    /** 5G 子菜单展开/收起：淡入淡出 + 轻移（跟随系统动画速率） */
    private boolean subMenuShow = false;

    private void animateSubMenu(boolean show) {
        subMenuShow = show;
        subBandChips.animate().cancel(); // 取消未完成动画，避免连续切换冲突
        if (show) {
            if (subBandChips.getVisibility() != View.VISIBLE) {
                subBandChips.setVisibility(View.VISIBLE);
                subBandChips.setAlpha(0f);
                subBandChips.setTranslationY(-dp(8));
                subBandChips.animate().alpha(1f).translationY(0f).setDuration(160).start();
            }
        } else {
            if (subBandChips.getVisibility() == View.VISIBLE) {
                subBandChips.animate().alpha(0f).translationY(-dp(8)).setDuration(130)
                        .withEndAction(() -> {
                            if (!subMenuShow) subBandChips.setVisibility(View.GONE);
                        }).start();
            }
        }
    }

    private void updateBandChips() {
        boolean in5g = bandFilter >= ChannelChartView.FILTER_5G_LOW && bandFilter <= ChannelChartView.FILTER_5G_HIGH;
        if (!chipInit) {
            // 首次：直接设置初始状态，不播动画
            chipInit = true;
            chipAll.setBackgroundResource(bandFilter == ChannelChartView.FILTER_ALL ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip2g.setBackgroundResource(bandFilter == ChannelChartView.FILTER_2G ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip5g.setBackgroundResource(in5g ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip6g.setBackgroundResource(bandFilter == ChannelChartView.FILTER_6G ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip5gLow.setBackgroundResource(bandFilter == ChannelChartView.FILTER_5G_LOW ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip5gMid.setBackgroundResource(bandFilter == ChannelChartView.FILTER_5G_MID ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            chip5gHigh.setBackgroundResource(bandFilter == ChannelChartView.FILTER_5G_HIGH ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            subBandChips.setVisibility(in5g ? View.VISIBLE : View.GONE);
            chipStates.put(chipAll, bandFilter == ChannelChartView.FILTER_ALL);
            chipStates.put(chip2g, bandFilter == ChannelChartView.FILTER_2G);
            chipStates.put(chip5g, in5g);
            chipStates.put(chip6g, bandFilter == ChannelChartView.FILTER_6G);
            chipStates.put(chip5gLow, bandFilter == ChannelChartView.FILTER_5G_LOW);
            chipStates.put(chip5gMid, bandFilter == ChannelChartView.FILTER_5G_MID);
            chipStates.put(chip5gHigh, bandFilter == ChannelChartView.FILTER_5G_HIGH);
            return;
        }
        setChipState(chipAll, bandFilter == ChannelChartView.FILTER_ALL);
        setChipState(chip2g, bandFilter == ChannelChartView.FILTER_2G);
        setChipState(chip5g, in5g);
        setChipState(chip6g, bandFilter == ChannelChartView.FILTER_6G);
        animateSubMenu(in5g);
        setChipState(chip5gLow, bandFilter == ChannelChartView.FILTER_5G_LOW);
        setChipState(chip5gMid, bandFilter == ChannelChartView.FILTER_5G_MID);
        setChipState(chip5gHigh, bandFilter == ChannelChartView.FILTER_5G_HIGH);
    }

    /** 分页切换：滚动时长跟随系统动画速率（ANIMATOR_DURATION_SCALE），跨页按页数放大时长 */
    private void smoothSetChannelItem(int position) {
        if (channelPager == null) return;
        int cur = channelPager.getCurrentItem();
        if (position == cur) return;
        // 取消进行中的动画，结束伪拖拽
        if (pagerAnim != null) {
            pagerAnim.cancel();
            try { channelPager.endFakeDrag(); } catch (Exception ignored) {}
            pagerAnim = null;
        }
        float scale = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale <= 0f) {
            channelPager.setCurrentItem(position, false);
            return;
        }
        float width = Math.max(1f, channelPager.getWidth());
        float total = (position - cur) * width;
        long dur = Math.max(80, (long) (Math.abs(position - cur) * 250 * scale));
        try {
            channelPager.beginFakeDrag();
        } catch (Exception e) {
            channelPager.setCurrentItem(position, true);
            return;
        }
        final float[] last = {0f};
        ValueAnimator va = ValueAnimator.ofFloat(0f, total);
        va.setDuration(dur);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            try {
                channelPager.fakeDragBy(f - last[0]);
                last[0] = f;
            } catch (Exception ignored) {
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                try { channelPager.endFakeDrag(); } catch (Exception ignored) {}
                pagerAnim = null;
                channelPager.setCurrentItem(position, false);
            }
        });
        pagerAnim = va;
        va.start();
    }

    /** 信道图分页：仅顶部按钮切换（禁用手势翻页，滑动留给图表切换相邻热点），按钮联动 */
    private void setupChannelPager() {
        channelAdapter = new ChannelPageAdapter(item -> showApDetail(item, false), // 信道页长按：顶部吐出
                new ChannelChartView.OnSamplingControl() {
                    @Override
                    public void onSamplingPaused() { MainActivity.this.onSamplingPaused(); }

                    @Override
                    public void onSamplingResumed() { MainActivity.this.onSamplingResumed(); }
                });
        channelPager.setAdapter(channelAdapter);
        channelPager.setOffscreenPageLimit(3);
        channelPager.setUserInputEnabled(false); // 取消滑动翻页
        channelPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 动画滚动期间保持目标高亮，由动画结束对齐，避免中间页闪烁
                if (pagerAnim != null) return;
                bandFilter = position;
                updateBandChips();
            }
        });
    }

    public void onScanClick(View v) {
        if (scanner != null) {
            boolean ok = scanner.requestScan();
            if (!ok) Toast.makeText(this, "扫描被系统限频，请稍候", Toast.LENGTH_SHORT).show();
        }
    }

    public void onAutoClick(View v) {
        autoScan = !autoScan;
        tvAuto.setText(autoScan ? "自动:开" : "自动:关");
        tvAuto.setBackgroundResource(autoScan ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        if (autoScan && scanner != null) scanner.requestScan();
    }

    // ---------- 户型图 ----------
    private void setupFloor() {
        findViewById(R.id.btn_import).setOnClickListener(v -> imagePicker.launch("image/*"));
        findViewById(R.id.btn_undo).setOnClickListener(v -> {
            floorView.undoLast();
            updateFloorStatus();
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            floorView.clearSamples();
            updateFloorStatus();
        });
        findViewById(R.id.btn_export).setOnClickListener(v -> exportHeatmap());

        floorView.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP && floorView.hasFloor()) {
                RectF rect = floorView.imageRect();
                if (rect.contains(ev.getX(), ev.getY())) {
                    float nx = (ev.getX() - rect.left) / rect.width();
                    float ny = (ev.getY() - rect.top) / rect.height();
                    nx = Math.max(0f, Math.min(1f, nx));
                    ny = Math.max(0f, Math.min(1f, ny));
                    String ssid = "?";
                    int dbm = -100;
                    if (!lastResults.isEmpty()) {
                        ssid = lastResults.get(0).ssid;
                        dbm = lastResults.get(0).level;
                    }
                    floorView.addSample(nx, ny, dbm, ssid);
                    updateFloorStatus();
                }
            }
            return true;
        });
        updateFloorStatus();
    }

    private void updateFloorStatus() {
        tvFloorStatus.setText(floorView.hasFloor()
                ? "底图已加载 · 采样点 " + floorView.sampleCount() + " 个 · 点击底图采样（取当前最强信号）"
                : "未加载底图");
    }

    private void loadFloorImage(Uri uri) {
        try {
            Bitmap bmp = decodeSampledBitmap(uri);
            if (bmp == null) {
                Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
                return;
            }
            floorView.setFloor(bmp);
            updateFloorStatus();
        } catch (Exception e) {
            Toast.makeText(this, "图片解析失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap decodeSampledBitmap(Uri uri) throws Exception {
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
        opt.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeStream(is, null, opt);
        if (is != null) is.close();
        int maxDim = 2048;
        int sample = 1;
        while (Math.max(opt.outWidth, opt.outHeight) / sample > maxDim) sample *= 2;
        is = getContentResolver().openInputStream(uri);
        opt = new android.graphics.BitmapFactory.Options();
        opt.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeStream(is, null, opt);
    }

    private void exportHeatmap() {
        if (!floorView.hasFloor()) {
            Toast.makeText(this, "请先导入户型图", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap out = Bitmap.createBitmap(floorView.getWidth(), floorView.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            floorView.draw(c);

            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "WiFiRadar");
            if (!dir.exists()) dir.mkdirs();
            String name = "heatmap_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, "已导出: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 设置 ----------
    private Dialog settingsDialog = null;

    private void applyLongPressSetting() {
        SharedPreferences sp = getSharedPreferences("wifiradar", MODE_PRIVATE);
        long lp = sp.getLong("long_press_ms", 450);
        if (channelAdapter != null) channelAdapter.setLongPressMs(lp);
    }

    private void applyFineSwipeSetting() {
        SharedPreferences sp = getSharedPreferences("wifiradar", MODE_PRIVATE);
        boolean on = sp.getBoolean("fine_swipe", false);
        if (channelAdapter != null) channelAdapter.setFineSwipe(on);
    }

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        settingsDialog.setContentView(R.layout.settings_dialog);
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView d250 = settingsDialog.findViewById(R.id.set_lp_250);
        TextView d450 = settingsDialog.findViewById(R.id.set_lp_450);
        TextView d700 = settingsDialog.findViewById(R.id.set_lp_700);
        TextView d1000 = settingsDialog.findViewById(R.id.set_lp_1000);
        TextView done = settingsDialog.findViewById(R.id.set_done);

        SharedPreferences sp = getSharedPreferences("wifiradar", MODE_PRIVATE);
        final long[] cur = {sp.getLong("long_press_ms", 450)};
        final TextView[] opts = {d250, d450, d700, d1000};
        final long[] vals = {250, 450, 700, 1000};

        Runnable refresh = () -> {
            for (int i = 0; i < opts.length; i++) {
                opts[i].setBackgroundResource(cur[0] == vals[i] ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            }
        };
        refresh.run();

        for (int i = 0; i < opts.length; i++) {
            final long v = vals[i];
            opts[i].setOnClickListener(x -> {
                cur[0] = v;
                sp.edit().putLong("long_press_ms", v).apply();
                if (channelAdapter != null) channelAdapter.setLongPressMs(v);
                refresh.run();
            });
        }

        // 精细滑动开关：选中热点后，在图表空白区域滑动可逐根切换相邻热点
        TextView fine = settingsDialog.findViewById(R.id.set_fine_swipe);
        final boolean[] fineOn = {sp.getBoolean("fine_swipe", false)};
        Runnable refreshFine = () -> {
            fine.setText("滑动空白精细选择：" + (fineOn[0] ? "开" : "关"));
            fine.setBackgroundResource(fineOn[0] ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        };
        refreshFine.run();
        fine.setOnClickListener(x -> {
            fineOn[0] = !fineOn[0];
            sp.edit().putBoolean("fine_swipe", fineOn[0]).apply();
            if (channelAdapter != null) channelAdapter.setFineSwipe(fineOn[0]);
            refreshFine.run();
        });

        done.setOnClickListener(x -> settingsDialog.dismiss());
        settingsDialog.setOnDismissListener(d -> settingsDialog = null);
        settingsDialog.show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) scanner.stop();
    }
}
