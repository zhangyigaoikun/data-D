package com.oscar.wifiradar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 户型图底图 + 信号采样点覆盖层 */
public class FloorPlanView extends View {

    public static class Sample {
        public final float nx, ny;   // 图片空间归一化坐标 0..1
        public final int dbm;
        public final String ssid;
        public final long time;

        Sample(float nx, float ny, int dbm, String ssid) {
            this.nx = nx; this.ny = ny; this.dbm = dbm; this.ssid = ssid;
            this.time = System.currentTimeMillis();
        }
    }

    private Bitmap floor = null;
    private final List<Sample> samples = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FloorPlanView(Context c) { this(c, null); }
    public FloorPlanView(Context c, AttributeSet a) { this(c, a, 0); }
    public FloorPlanView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        setBackgroundColor(0xFF14161A);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(dp(12));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setFloor(Bitmap b) {
        floor = b;
        samples.clear();
        invalidate();
    }

    public boolean hasFloor() {
        return floor != null;
    }

    public void addSample(float nx, float ny, int dbm, String ssid) {
        samples.add(new Sample(nx, ny, dbm, ssid));
        invalidate();
    }

    public void undoLast() {
        if (!samples.isEmpty()) {
            samples.remove(samples.size() - 1);
            invalidate();
        }
    }

    public void clearSamples() {
        samples.clear();
        invalidate();
    }

    public int sampleCount() {
        return samples.size();
    }

    public List<Sample> getSamples() {
        return samples;
    }

    /** 底图在 View 内的 fitCenter 显示矩形 */
    public RectF imageRect() {
        RectF rect = new RectF(0, 0, getWidth(), getHeight());
        if (floor == null) return rect;
        float vw = getWidth(), vh = getHeight();
        float scale = Math.min(vw / floor.getWidth(), vh / floor.getHeight());
        float dw = floor.getWidth() * scale, dh = floor.getHeight() * scale;
        rect.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f);
        return rect;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF rect = imageRect();
        if (floor != null) {
            canvas.drawBitmap(floor, null, rect, null);
        } else {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0x77FFFFFF);
            canvas.drawText("点击右上角「导入户型图」加载底图", getWidth() / 2f, getHeight() / 2f, textPaint);
        }
        for (Sample s : samples) {
            float x = rect.left + s.nx * rect.width();
            float y = rect.top + s.ny * rect.height();
            float r = dp(13);
            paint.setColor(0x66000000);
            canvas.drawCircle(x, y, r + dp(2), paint);
            paint.setColor(ScanResultItem.signalColor(s.dbm));
            canvas.drawCircle(x, y, r, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.FILL);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(s.dbm), x, y + dp(4), textPaint);
        }
    }
}
