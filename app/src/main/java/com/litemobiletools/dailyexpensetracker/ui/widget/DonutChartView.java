package com.litemobiletools.dailyexpensetracker.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/** Minimal native donut chart. Draws a neutral track when there is nothing to show. */
public class DonutChartView extends View {

    private static final float START_ANGLE = -90f;
    private static final float GAP_DEGREES = 2.5f;

    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private long[] values = new long[0];
    private int[] colors = new int[0];

    public DonutChartView(@NonNull Context context) {
        this(context, null);
    }

    public DonutChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        slicePaint.setStyle(Paint.Style.STROKE);
        slicePaint.setStrokeCap(Paint.Cap.BUTT);
        trackPaint.setStyle(Paint.Style.STROKE);
    }

    /** @param values non-negative amounts; @param colors one color per value */
    public void setSlices(@NonNull long[] values, @NonNull @ColorInt int[] colors) {
        if (values.length != colors.length) {
            throw new IllegalArgumentException("values and colors must have the same length");
        }
        this.values = values.clone();
        this.colors = colors.clone();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) {
            return;
        }
        float stroke = size * 0.14f;
        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f + stroke / 2f;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f + stroke / 2f;
        bounds.set(left, top, left + size - stroke, top + size - stroke);

        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant));
        canvas.drawOval(bounds, trackPaint);

        long total = 0;
        for (long v : values) {
            total += v;
        }
        if (total <= 0) {
            return;
        }
        slicePaint.setStrokeWidth(stroke);
        float gap = values.length > 1 ? GAP_DEGREES : 0f;
        float angle = START_ANGLE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) {
                continue;
            }
            float sweep = 360f * values[i] / total;
            slicePaint.setColor(colors[i]);
            canvas.drawArc(bounds, angle + gap / 2f, Math.max(sweep - gap, 0.5f), false, slicePaint);
            angle += sweep;
        }
    }
}
