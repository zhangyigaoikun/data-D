package com.oscar.wifiradar;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 信道图分页适配器：全部 / 2.4G / 5G / 6G 四个页面 */
public class ChannelPageAdapter extends RecyclerView.Adapter<ChannelPageAdapter.VH> {

    public static final int COUNT = 6;
    private static final int[] FILTERS = {
            ChannelChartView.FILTER_ALL,
            ChannelChartView.FILTER_2G,
            ChannelChartView.FILTER_5G_LOW,
            ChannelChartView.FILTER_5G_MID,
            ChannelChartView.FILTER_5G_HIGH,
            ChannelChartView.FILTER_6G
    };

    private final ChannelChartView.OnApLongPress longPressListener;
    private final ChannelChartView.OnSamplingControl samplingListener;
    private final ChannelChartView[] charts = new ChannelChartView[COUNT];
    private List<ScanResultItem> data = new ArrayList<>();
    private long longPressMs = 450;

    public ChannelPageAdapter(ChannelChartView.OnApLongPress l, ChannelChartView.OnSamplingControl s) {
        this.longPressListener = l;
        this.samplingListener = s;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 直接把 ChannelChartView 作为页面根视图（不能再包容器，否则挂载冲突闪退）
        ChannelChartView cv = new ChannelChartView(parent.getContext());
        cv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cv.setOnApLongPressListener(longPressListener);
        cv.setOnSamplingControlListener(samplingListener);
        cv.setLongPressMs(longPressMs);
        charts[viewType] = cv; // viewType == position（见 getItemViewType）
        return new VH(cv);
    }

    /** 应用设置：长按触发时长 */
    public void setLongPressMs(long ms) {
        longPressMs = ms;
        for (ChannelChartView c : charts) {
            if (c != null) c.setLongPressMs(ms);
        }
    }

    /** 应用设置：精细滑动开关（选中后空白区域滑动逐根切换） */
    public void setFineSwipe(boolean on) {
        for (ChannelChartView c : charts) {
            if (c != null) c.setFineSwipe(on);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.chart.setFilter(FILTERS[position]);
        h.chart.setData(data);
    }

    @Override
    public int getItemCount() {
        return COUNT;
    }

    /** 更新全部已创建页面的数据；不再 notifyDataSetChanged（避免每次扫描全量重绑页面，已创建页由遍历更新，未创建页 bind 时取最新 data） */
    public void setData(List<ScanResultItem> list) {
        data = list == null ? new ArrayList<>() : list;
        for (ChannelChartView c : charts) {
            if (c != null) c.setData(data);
        }
    }

    /** 任一页面有选中热点：选中期间暂停图表刷新（冻结柱子/气泡，取消选中后恢复实时） */
    public boolean hasSelection() {
        for (ChannelChartView c : charts) {
            if (c != null && c.hasSelection()) return true;
        }
        return false;
    }

    public ChannelChartView getChart(int position) {
        return charts[position];
    }

    static class VH extends RecyclerView.ViewHolder {
        final ChannelChartView chart;
        VH(ChannelChartView c) {
            super(c);
            chart = c;
        }
    }
}
