package com.oscar.wifiradar;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 扫描结果列表适配器 */
public class ApAdapter extends RecyclerView.Adapter<ApAdapter.VH> {

    public interface OnApClick {
        void onApClick(ScanResultItem item);
    }

    private final List<ScanResultItem> items = new ArrayList<>();
    private final OnApClick click;
    /** 上次列表内容指纹：数据无变化时跳过全量刷新，避免每 3 秒打断列表滚动 */
    private String lastKey = "";

    public ApAdapter(OnApClick c) {
        this.click = c;
    }

    public void setItems(List<ScanResultItem> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (ScanResultItem it : list) {
                sb.append(it.bssid).append('|').append(it.level).append('|').append(it.channel).append(';');
            }
        }
        String key = sb.toString();
        if (key.equals(lastKey)) return; // 数据无变化：跳过全量刷新
        lastKey = key;
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<ScanResultItem> getItems() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ap, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ScanResultItem it = items.get(position);
        h.ssid.setText(it.ssid);
        h.sub.setText(it.bssid + " · " + it.standard + (it.encrypted ? " · " + it.security : " · 开放"));
        h.signal.setText(it.level + " dBm");
        h.quality.setText(ScanResultItem.signalText(it.level));
        h.quality.setTextColor(ScanResultItem.signalColor(it.level));
        h.channel.setText("CH " + it.channel);
        h.band.setText(it.band);
        h.band.setTextColor(ScanResultItem.signalColor(it.level));

        int frac = Math.max(0, Math.min(100, (it.level + 100) * 100 / 90));
        LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) h.bar.getLayoutParams();
        LinearLayout.LayoutParams restLp = (LinearLayout.LayoutParams) h.barRest.getLayoutParams();
        barLp.weight = frac;
        restLp.weight = 100 - frac;
        h.bar.setLayoutParams(barLp);
        h.barRest.setLayoutParams(restLp);
        h.bar.setBackgroundColor(ScanResultItem.signalColor(it.level));

        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) click.onApClick(it);
            }
        });
        // 长按同样查看详情（与点击等效）
        h.root.setOnLongClickListener(v -> {
            if (click != null) click.onApClick(it);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView ssid, sub, signal, quality, channel, band;
        final View bar, barRest;

        VH(View v) {
            super(v);
            root = v;
            ssid = v.findViewById(R.id.tv_ssid);
            sub = v.findViewById(R.id.tv_sub);
            signal = v.findViewById(R.id.tv_signal);
            quality = v.findViewById(R.id.tv_quality);
            channel = v.findViewById(R.id.tv_channel);
            band = v.findViewById(R.id.tv_band);
            bar = v.findViewById(R.id.bar);
            barRest = v.findViewById(R.id.bar_rest);
        }
    }
}
