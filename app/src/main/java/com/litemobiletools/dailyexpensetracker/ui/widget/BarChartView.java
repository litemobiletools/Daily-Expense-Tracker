package com.litemobiletools.dailyexpensetracker.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * Minimal native bar chart (one bar per value, e.g. spending per day). The tallest bar is drawn in
 * the accent colour. Callers must set a content description summarising the data, since the bars
 * themselves are not readable by screen readers.
 */
public class BarChartView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private long[] values = new long[0];
    /** Axis labels under the first, middle and last bar; null means day numbers 1, middle, count. */
    @Nullable private String[] axisLabels;

    public BarChartView(@NonNull Context context) {
        this(context, null);
    }

    public BarChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        basePaint.setStrokeWidth(density);
        textPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setValues(@NonNull long[] values) {
        this.values = values.clone();
        invalidate();
    }

    /** Custom labels for the first, middle and last bar (for example short dates). */
    public void setAxisLabels(@NonNull String first, @NonNull String middle, @NonNull String last) {
        this.axisLabels = new String[]{first, middle, last};
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int count = values.length;
        if (count == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline);
        int label = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        basePaint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant));
        textPaint.setColor(label);

        float labelHeight = textPaint.getTextSize() + 4 * density;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float baseline = getHeight() - getPaddingBottom() - labelHeight;
        float width = right - left;
        float chartHeight = baseline - top;
        if (width <= 0 || chartHeight <= 0) {
            return;
        }

        long max = 0;
        int maxIndex = -1;
        for (int i = 0; i < count; i++) {
            if (values[i] > max) {
                max = values[i];
                maxIndex = i;
            }
        }

        float slot = width / count;
        float gap = Math.min(slot * 0.25f, 3 * density);
        for (int i = 0; i < count; i++) {
            if (values[i] <= 0 || max <= 0) {
                continue;
            }
            float h = Math.max(2 * density, chartHeight * values[i] / (float) max);
            float x = left + i * slot;
            bar.set(x + gap / 2f, baseline - h, x + slot - gap / 2f, baseline);
            barPaint.setColor(i == maxIndex ? accent : muted);
            canvas.drawRoundRect(bar, 2 * density, 2 * density, barPaint);
        }
        canvas.drawLine(left, baseline, right, baseline, basePaint);

        // Day-of-month labels: first, middle and last.
        float textY = getHeight() - getPaddingBottom() - 2 * density;
        String first = axisLabels != null ? axisLabels[0] : "1";
        String middle = axisLabels != null ? axisLabels[1] : String.valueOf((count + 1) / 2);
        String last = axisLabels != null ? axisLabels[2] : String.valueOf(count);
        // Keep labels inside the view even when they are wider than a bar.
        textPaint.setTextAlign(Paint.Align.LEFT);
        drawLabel(canvas, first, left, textY);
        if (count > 10) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            drawLabel(canvas, middle, left + ((count + 1) / 2 - 0.5f) * slot, textY);
        }
        if (count > 1) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            drawLabel(canvas, last, right, textY);
        }
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawLabel(Canvas canvas, String text, float x, float y) {
        canvas.drawText(text, x, y, textPaint);
    }
}
