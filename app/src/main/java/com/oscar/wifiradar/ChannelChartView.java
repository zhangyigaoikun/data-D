package com.oscar.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 信道分布图：按频段展示各信道上的 AP 与信号强度。
 * 交互：点击柱子 = 选中并高亮 + 顶部气泡显示信息，左右滑动切换相邻热点；
 * 长按 = 直接查看详情（时长可在设置中调整）。
 * 设置项：长按时长、精细滑动（选中后空白区域滑动逐根切换）。
 */
public class ChannelChartView extends View {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_2G = 1;
    public static final int FILTER_5G_LOW = 2;   // CH 36-64（含 DFS 52-64）
    public static final int FILTER_5G_MID = 3;   // CH 100-144（DFS）
    public static final int FILTER_5G_HIGH = 4;  // CH 149-165
    public static final int FILTER_6G = 5;

    public interface OnApLongPress {
        void onApLongPress(ScanResultItem item);
    }

    /** 手指按下/离开时回调，用于交互期间暂停自动扫描采样，避免刷新打断选中/滑动 */
    public interface OnSamplingControl {
        void onSamplingPaused();
        void onSamplingResumed();
    }

    private static final long DEFAULT_LONG_PRESS_MS = 450;

    private final List<ScanResultItem> items = new ArrayList<>();
    private int filter = FILTER_ALL;
    private OnApLongPress longPressListener = null;
    private OnSamplingControl samplingListener = null;
    private ScanResultItem selectedItem = null;
    private long longPressMs = DEFAULT_LONG_PRESS_MS;
    /** 精细滑动开关：选中热点后，在空白区域滑动按位移逐根切换（设置内可关） */
    private boolean fineSwipe = false;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 梯形柱 Path 复用（避免每帧新建对象产生 GC 压力） */
    private final Path barPath = new Path();

    // 手势：点击选中/取消；水平滑动切换相邻热点；长按直接查看详情
    private float downX, downY;
    private long downTime;
    private boolean downOnBar = false;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            handleLongPress(downX, downY);
        }
    };

    public ChannelChartView(Context c) { this(c, null); }
    public ChannelChartView(Context c, AttributeSet a) { this(c, a, 0); }
    public ChannelChartView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(0x33FFFFFF);
        textPaint.setColor(0xAAFFFFFF);
        textPaint.setTextSize(dp(11));
        bubblePaint.setColor(0xE6121417);
    }

    public void setData(List<ScanResultItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        // 选中项可能已消失
        if (selectedItem != null) {
            boolean still = false;
            for (ScanResultItem it : items) {
                if (it.bssid.equals(selectedItem.bssid)) { still = true; break; }
            }
            if (!still) selectedItem = null;
        }
        invalidate();
    }

    public void setFilter(int f) {
        filter = f;
        invalidate();
    }

    public void setOnApLongPressListener(OnApLongPress l) {
        this.longPressListener = l;
    }

    public void setOnSamplingControlListener(OnSamplingControl l) {
        this.samplingListener = l;
    }

    /** 设置长按触发时长（来自应用设置） */
    public void setLongPressMs(long ms) {
        longPressMs = ms;
    }

    /** 设置精细滑动开关（来自应用设置）：开 = 选中后空白区域滑动逐根精细切换 */
    public void setFineSwipe(boolean on) {
        fineSwipe = on;
    }

    /** 是否有选中热点：选中期间 MainActivity 暂停图表刷新，避免柱子/气泡随扫描跳动 */
    public boolean hasSelection() {
        return selectedItem != null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTime = System.currentTimeMillis();
                downOnBar = findApAt(downX, downY) != null; // 按下点是否在柱子上（精细模式判定用）
                // 手指按下：暂停自动采样，防止刷新打断选中/滑动/长按
                if (samplingListener != null) samplingListener.onSamplingPaused();
                if (getHandler() != null) {
                    getHandler().removeCallbacks(longPressRunnable);
                    getHandler().postDelayed(longPressRunnable, longPressMs);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                float dist = (float) Math.hypot(dx, dy);
                // 精细模式：选中热点后在空白区域滑动，位移 10dp 起即可逐根切换
                boolean fine = fineSwipe && selectedItem != null && !downOnBar;
                if (dist > dp(24) || (fine && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy))) {
                    if (getHandler() != null) getHandler().removeCallbacks(longPressRunnable);
                    if (Math.abs(dx) > Math.abs(dy)) {
                        float step = fine ? dp(10) : dp(40);
                        if (Math.abs(dx) >= step) {
                            // 水平滑动 = 切换相邻热点（精细模式按位移比例逐根走）
                            int n = fine ? Math.max(1, (int) (Math.abs(dx) / step)) : 1;
                            for (int i = 0; i < n; i++) selectNeighbor(dx > 0 ? 1 : -1);
                            downX = ev.getX();
                            downY = ev.getY();
                        }
                    }
                    // 纵向滑动无动作，仅消费事件（防止与点击/切换冲突）
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (getHandler() != null) getHandler().removeCallbacks(longPressRunnable);
                if (samplingListener != null) samplingListener.onSamplingResumed();
                float upDx = ev.getX() - downX;
                float upDy = ev.getY() - downY;
                if (System.currentTimeMillis() - downTime < longPressMs
                        && Math.hypot(upDx, upDy) <= dp(24)) {
                    // 快速点击：选中/取消（与按住阈值一致，手抖不误判）
                    ScanResultItem hit = findApAt(downX, downY);
                    if (hit != null) {
                        selectedItem = (selectedItem != null && selectedItem.bssid.equals(hit.bssid)) ? null : hit;
                    } else {
                        selectedItem = null;
                    }
                    invalidate();
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                if (getHandler() != null) getHandler().removeCallbacks(longPressRunnable);
                if (samplingListener != null) samplingListener.onSamplingResumed();
                return false;
        }
        return super.onTouchEvent(ev);
    }

    /** 滑动切换：按当前页显示顺序切换相邻热点（段内按信道、跨段无缝衔接）；无选中时以滑动起点命中的柱子为基准 */
    private void selectNeighbor(int dir) {
        ScanResultItem base = selectedItem;
        if (base == null) base = findApAt(downX, downY);
        if (base == null) return;
        List<ScanResultItem> all = filteredShown();
        if (all.isEmpty()) return;
        // 排序：2.4GHz → 5GHz → 6GHz，段内按信道，与图表绘制顺序一致
        Collections.sort(all, (a, b) -> {
            int ra = bandRank(a.band), rb = bandRank(b.band);
            if (ra != rb) return ra - rb;
            return a.channel - b.channel;
        });
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).bssid.equals(base.bssid)) { idx = i; break; }
        }
        int next = idx < 0 ? 0 : idx + dir;
        if (next < 0 || next >= all.size()) return;
        selectedItem = all.get(next);
        invalidate();
    }

    /** 频段顺序权重：2.4GHz=0、5GHz=1、6GHz=2 */
    private int bandRank(String band) {
        if ("2.4GHz".equals(band)) return 0;
        if ("5GHz".equals(band)) return 1;
        return 2;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 当前筛选后的 AP 列表 */
    private List<ScanResultItem> filteredShown() {
        List<ScanResultItem> shown = new ArrayList<>();
        for (ScanResultItem it : items) {
            boolean ok;
            switch (filter) {
                case FILTER_2G:
                    ok = it.band.equals("2.4GHz");
                    break;
                case FILTER_5G_LOW:
                    ok = it.band.equals("5GHz") && it.channel >= 36 && it.channel <= 64;
                    break;
                case FILTER_5G_MID:
                    ok = it.band.equals("5GHz") && it.channel >= 100 && it.channel <= 144;
                    break;
                case FILTER_5G_HIGH:
                    ok = it.band.equals("5GHz") && it.channel >= 149 && it.channel <= 165;
                    break;
                case FILTER_6G:
                    ok = it.band.equals("6GHz");
                    break;
                default:
                    ok = true;
            }
            if (ok) shown.add(it);
        }
        return shown;
    }

    private List<BandSection> currentBands() {
        List<BandSection> bands = new ArrayList<>();
        if (filter == FILTER_ALL || filter == FILTER_2G) {
            bands.add(new BandSection("2.4GHz", "2.4GHz", 1, 13));
        }
        if (filter == FILTER_ALL) {
            bands.add(new BandSection("5GHz", "5GHz", 36, 165));
        } else if (filter == FILTER_5G_LOW) {
            bands.add(new BandSection("5GHz", "5GHz 低", 36, 64));
        } else if (filter == FILTER_5G_MID) {
            bands.add(new BandSection("5GHz", "5GHz 中", 100, 144));
        } else if (filter == FILTER_5G_HIGH) {
            bands.add(new BandSection("5GHz", "5GHz 高", 149, 165));
        }
        if (filter == FILTER_ALL || filter == FILTER_6G) {
            bands.add(new BandSection("6GHz", "6GHz", 1, 233));
        }
        return bands;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (items.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0x88FFFFFF);
            canvas.drawText("暂无扫描结果", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }
        List<ScanResultItem> shown = filteredShown();
        if (shown.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0x88FFFFFF);
            canvas.drawText("该频段暂无热点", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        List<BandSection> bands = currentBands();
        float labelW = dp(64);
        float pad = dp(10);
        float topPad = dp(6);
        float secGap = dp(14);
        float secH = (getHeight() - topPad * 2 - secGap * (bands.size() - 1)) / bands.size();

        int idx = 0;
        for (BandSection sec : bands) {
            float top = topPad + idx * (secH + secGap);
            drawSection(canvas, sec, shown, labelW, pad, top, secH);
            idx++;
        }
    }

    private void drawSection(Canvas canvas, BandSection sec, List<ScanResultItem> shown,
                             float labelW, float pad, float top, float secH) {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0xCCFFFFFF);
        canvas.drawText(sec.name, pad, top + dp(12), textPaint);
        float plotTop = top + dp(18);
        float plotBottom = top + secH - dp(14);
        float plotH = plotBottom - plotTop;
        float plotLeft = pad + labelW;
        float plotRight = getWidth() - pad;
        float plotW = plotRight - plotLeft;

        gridPaint.setColor(0x22FFFFFF);
        for (int ref : new int[]{-40, -60, -80}) {
            float y = plotBottom - (ref - (-100)) / 70f * plotH;
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint);
        }
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(0x77FFFFFF);
        for (int ref : new int[]{-40, -60, -80}) {
            float y = plotBottom - (ref - (-100)) / 70f * plotH;
            canvas.drawText(String.valueOf(ref), plotRight - dp(2), y - dp(2), textPaint);
        }

        List<ScanResultItem> inBand = new ArrayList<>();
        for (ScanResultItem it : shown) {
            if (it.band.equals(sec.bandKey) && it.channel >= sec.minCh && it.channel <= sec.maxCh) {
                inBand.add(it);
            }
        }
        if (inBand.isEmpty()) return;

        Map<Integer, List<ScanResultItem>> byCh = new TreeMap<>();
        for (ScanResultItem it : inBand) {
            List<ScanResultItem> l = byCh.get(it.channel);
            if (l == null) { l = new ArrayList<>(); byCh.put(it.channel, l); }
            l.add(it);
        }

        float chRange = sec.maxCh - sec.minCh;
        barPaint.setStyle(Paint.Style.FILL);
        for (Map.Entry<Integer, List<ScanResultItem>> e : byCh.entrySet()) {
            int ch = e.getKey();
            if (ch < sec.minCh || ch > sec.maxCh) continue;
            List<ScanResultItem> group = e.getValue();
            float cx = plotLeft + (ch - sec.minCh) / chRange * plotW;
            float barW = Math.min(dp(10), plotW / chRange * 0.7f);
            if (group.size() == 1) {
                // 单根：正常长方形柱
                ScanResultItem it = group.get(0);
                float frac = Math.max(0f, Math.min(1f, (it.level - (-100)) / 70f));
                float bh = frac * plotH;
                float x0 = cx - barW / 2f;
                barPaint.setColor(ScanResultItem.signalColor(it.level));
                canvas.drawRect(x0, plotBottom - bh, x0 + barW - dp(1), plotBottom, barPaint);
                if (it == selectedItem) {
                    barPaint.setStyle(Paint.Style.STROKE);
                    barPaint.setStrokeWidth(dp(2));
                    barPaint.setColor(0xFF4FC3F7);
                    canvas.drawRect(x0 - dp(1), plotBottom - bh, x0 + barW - dp(1), plotBottom, barPaint);
                    barPaint.setStyle(Paint.Style.FILL);
                }
                continue;
            }
            // 同信道多根：底对齐叠加成梯形堆（弱→强，强柱在顶层、形状更长），柱顶 ×N 角标提示
            List<ScanResultItem> stacked = new ArrayList<>(group);
            stacked.sort((a, b) -> Integer.compare(a.level, b.level));
            float maxBh = 0f;
            for (ScanResultItem it : stacked) {
                float frac = Math.max(0f, Math.min(1f, (it.level - (-100)) / 70f));
                float bh = frac * plotH;
                if (bh > maxBh) maxBh = bh;
                barPaint.setColor(ScanResultItem.signalColor(it.level));
                drawTrapezoid(canvas, cx, plotBottom, bh, barW);
            }
            // 角标 ×N：选中该叠加组时隐藏（气泡内已带 ×N，避免遮挡）
            if (selectedItem == null || !group.contains(selectedItem)) {
                drawCountBadge(canvas, cx, plotBottom - maxBh, plotTop, group.size());
            }
            // 选中高亮描边画在最顶层（不被强柱遮挡）
            for (ScanResultItem it : stacked) {
                if (it == selectedItem) {
                    float frac = Math.max(0f, Math.min(1f, (it.level - (-100)) / 70f));
                    barPaint.setStyle(Paint.Style.STROKE);
                    barPaint.setStrokeWidth(dp(2));
                    barPaint.setColor(0xFF4FC3F7);
                    drawTrapezoid(canvas, cx, plotBottom, frac * plotH, barW);
                    barPaint.setStyle(Paint.Style.FILL);
                }
            }
        }

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0x99FFFFFF);
        int step = (sec.maxCh - sec.minCh) <= 14 ? 2 : 16;
        for (int ch = sec.minCh; ch <= sec.maxCh; ch += step) {
            float cx = plotLeft + (ch - sec.minCh) / chRange * plotW;
            canvas.drawText(String.valueOf(ch), cx, plotBottom + dp(11), textPaint);
        }

        // 选中气泡：显示在选中柱子上方的段顶部（最后绘制，避免被柱子遮挡）
        if (selectedItem != null && inBand.contains(selectedItem)) {
            int grpCount = 1;
            List<ScanResultItem> grp = byCh.get(selectedItem.channel);
            if (grp != null) grpCount = grp.size();
            drawBubble(canvas, selectedItem, sec, plotLeft, plotRight, plotTop, chRange, plotW, grpCount);
        }
    }

    /** 绘制选中信息气泡（SSID + 信号/信道，叠加柱附带 ×N），类似抖音柱状图的浮标 */
    private void drawBubble(Canvas canvas, ScanResultItem sel, BandSection sec,
                            float plotLeft, float plotRight, float plotTop,
                            float chRange, float plotW, int grpCount) {
        String t1 = fitText(sel.ssid.isEmpty() ? "(隐藏网络)" : sel.ssid, dp(110));
        String t2 = sel.level + " dBm · CH " + sel.channel + " · " + sel.band;
        if (grpCount > 1) t2 += " · ×" + grpCount;

        float pad = dp(9);
        textPaint.setTextSize(dp(12));
        float w = Math.max(textPaint.measureText(t1), textPaint.measureText(t2)) + pad * 2;
        float h = dp(36);
        float cx = plotLeft + (sel.channel - sec.minCh) / chRange * plotW;
        float bx = cx - w / 2f;
        bx = Math.max(plotLeft, Math.min(bx, plotRight - w));
        float by = plotTop + dp(2);

        canvas.drawRoundRect(bx, by, bx + w, by + h, dp(8), dp(8), bubblePaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0xFFFFFFFF);
        canvas.drawText(t1, bx + pad, by + dp(16), textPaint);
        textPaint.setColor(0xFF4FC3F7);
        canvas.drawText(t2, bx + pad, by + dp(29), textPaint);
        textPaint.setTextSize(dp(11));
        textPaint.setColor(0xAAFFFFFF);
    }

    private String fitText(String s, float maxW) {
        if (textPaint.measureText(s) <= maxW) return s;
        while (s.length() > 1 && textPaint.measureText(s + "…") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** 命中检测：触摸位置对应的 AP，未命中返回 null */
    private ScanResultItem findApAt(float x, float y) {
        List<ScanResultItem> shown = filteredShown();
        if (shown.isEmpty()) return null;
        List<BandSection> bands = currentBands();

        float labelW = dp(64);
        float pad = dp(10);
        float topPad = dp(6);
        float secGap = dp(14);
        float secH = (getHeight() - topPad * 2 - secGap * (bands.size() - 1)) / bands.size();

        int idx = 0;
        for (BandSection sec : bands) {
            float top = topPad + idx * (secH + secGap);
            float plotTop = top + dp(18);
            float plotBottom = top + secH - dp(14);
            float plotLeft = pad + labelW;
            float plotRight = getWidth() - pad;
            float plotW = plotRight - plotLeft;

            // x 边界放宽到命中容差：最左/最右柱子的柱心恰在绘图区边缘，柱子有一半画在边界外，
            // 若仍限定 x∈[plotLeft, plotRight]，点柱子外侧的半边永远选不中
            if (y >= plotTop - dp(4) && y <= plotBottom + dp(20)
                    && x >= plotLeft - dp(48) && x <= plotRight + dp(48)) {
                List<ScanResultItem> inBand = new ArrayList<>();
                for (ScanResultItem it : shown) {
                    if (it.band.equals(sec.bandKey) && it.channel >= sec.minCh && it.channel <= sec.maxCh) {
                        inBand.add(it);
                    }
                }
                if (inBand.isEmpty()) return null;

                // 按柱子实际水平中心命中（与绘制布局一致）：叠加梯形同信道柱心重合，命中取信号最强一根
                float chRange = sec.maxCh - sec.minCh;
                Map<Integer, List<ScanResultItem>> byCh = new TreeMap<>();
                for (ScanResultItem it : inBand) {
                    List<ScanResultItem> l = byCh.get(it.channel);
                    if (l == null) { l = new ArrayList<>(); byCh.put(it.channel, l); }
                    l.add(it);
                }
                ScanResultItem best = null;
                float bestDist = Float.MAX_VALUE;
                for (Map.Entry<Integer, List<ScanResultItem>> e : byCh.entrySet()) {
                    int ch = e.getKey();
                    if (ch < sec.minCh || ch > sec.maxCh) continue;
                    List<ScanResultItem> group = e.getValue();
                    float cx = plotLeft + (ch - sec.minCh) / chRange * plotW;
                    float barW = Math.min(dp(10), plotW / chRange * 0.7f);
                    // 单根按柱心；叠加梯形同信道柱心重合，命中取信号最强的一根（视觉最显眼）
                    float d = Math.abs(x - cx);
                    if (d < bestDist) {
                        ScanResultItem strongest = group.get(0);
                        for (ScanResultItem g : group) if (g.level > strongest.level) strongest = g;
                        bestDist = d;
                        best = strongest;
                    }
                }
                // 命中容差按像素（约 48dp），避免 5G/6G 段信道密集导致按不中
                if (best != null && bestDist <= dp(48)) {
                    return best;
                }
                return null;
            }
            idx++;
        }
        return null;
    }

    /** 梯形柱：底部宽 barW、顶部收窄；信号越强 bh 越大（形状越长），多根叠加时像梯形堆 */
    private void drawTrapezoid(Canvas canvas, float cx, float bottom, float bh, float barW) {
        barPath.reset();
        float topW = Math.max(dp(2), barW * 0.55f);
        barPath.moveTo(cx - barW / 2f, bottom);
        barPath.lineTo(cx - topW / 2f, bottom - bh);
        barPath.lineTo(cx + topW / 2f, bottom - bh);
        barPath.lineTo(cx + barW / 2f, bottom);
        barPath.close();
        canvas.drawPath(barPath, barPaint);
    }

    /** 叠加提示角标：柱顶上方显示 ×N（N = 同信道 AP 数），单根不显示 */
    private void drawCountBadge(Canvas canvas, float cx, float topY, float plotTop, int count) {
        String s = "×" + count;
        textPaint.setTextSize(dp(10));
        float w = textPaint.measureText(s) + dp(8);
        float h = dp(13);
        float bx = cx - w / 2f;
        float by = Math.max(plotTop + dp(2), topY - h - dp(2));
        canvas.drawRoundRect(bx, by, bx + w, by + h, dp(6), dp(6), bubblePaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF4FC3F7);
        canvas.drawText(s, cx, by + dp(10), textPaint);
        textPaint.setTextSize(dp(11));
        textPaint.setColor(0xAAFFFFFF);
    }

    /** 长按：直接查看详情（面板从顶部吐出，不独占触控，系统返回键可关） */
    private void handleLongPress(float x, float y) {
        if (longPressListener == null) return;
        ScanResultItem hit = findApAt(x, y);
        if (hit == null) return;
        selectedItem = hit;
        invalidate();
        longPressListener.onApLongPress(hit);
    }

    private static class BandSection {
        final String bandKey; // 频段匹配键："2.4GHz" / "5GHz" / "6GHz"
        final String name;    // 显示名（子频段加后缀）
        final int minCh, maxCh;
        BandSection(String key, String n, int a, int b) {
            bandKey = key; name = n; minCh = a; maxCh = b;
        }
    }
}
