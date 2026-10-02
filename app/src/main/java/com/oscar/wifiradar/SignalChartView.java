package com.oscar.wifiradar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 信号强度历史曲线图 */
public class SignalChartView extends View {

    private final List<Float> history = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SignalChartView(Context c) { this(c, null); }
    public SignalChartView(Context c, AttributeSet a) { this(c, a, 0); }
    public SignalChartView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(0xFF4FC3F7);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        textPaint.setColor(0x99FFFFFF);
        textPaint.setTextSize(dp(10));
    }

    public void setData(List<Float> data) {
        history.clear();
        if (data != null) history.addAll(data);
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padL = dp(8), padR = dp(34), padT = dp(10), padB = dp(10);
        float w = getWidth(), h = getHeight();
        float plotL = padL, plotR = w - padR, plotT = padT, plotB = h - padB;
        float plotW = plotR - plotL, plotH = plotB - plotT;

        // 参考线 -40 / -60 / -80 / -90
        gridPaint.setColor(0x26FFFFFF);
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (int ref : new int[]{-40, -60, -80, -90}) {
            float y = plotB - (ref - (-100)) / 70f * plotH;
            canvas.drawLine(plotL, y, plotR, y, gridPaint);
            canvas.drawText(String.valueOf(ref), plotR + dp(2), y + dp(3), textPaint);
        }

        if (history.size() < 2) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0x88FFFFFF);
            canvas.drawText("采样中…", plotL + plotW / 2f, plotT + plotH / 2f, textPaint);
            return;
        }

        float minV = -100, maxV = -30;
        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < history.size(); i++) {
            float v = history.get(i);
            float x = plotL + i / (float) (history.size() - 1) * plotW;
            float y = plotB - (Math.max(minV, Math.min(maxV, v)) - minV) / (maxV - minV) * plotH;
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        canvas.drawPath(line, linePaint);

        // 渐变填充
        fill.lineTo(plotR, plotB);
        fill.lineTo(plotL, plotB);
        fill.close();
        fillPaint.setShader(new LinearGradient(0, plotT, 0, plotB,
                0x334FC3F7, 0x004FC3F7, Shader.TileMode.CLAMP));
        canvas.drawPath(fill, fillPaint);
        fillPaint.setShader(null);

        // 最新点
        float lx = plotR;
        float lv = history.get(history.size() - 1);
        float ly = plotB - (Math.max(minV, Math.min(maxV, lv)) - minV) / (maxV - minV) * plotH;
        linePaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(0xFF4FC3F7);
        canvas.drawCircle(lx, ly, dp(4), linePaint);
        linePaint.setStyle(Paint.Style.STROKE);

        // 当前值
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xCCFFFFFF);
        canvas.drawText(Math.round(lv) + " dBm", plotL + plotW / 2f, plotT + dp(11), textPaint);
    }
}
