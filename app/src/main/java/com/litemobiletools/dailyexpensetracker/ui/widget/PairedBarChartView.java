package com.litemobiletools.dailyexpensetracker.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.litemobiletools.dailyexpensetracker.R;

/**
 * Native grouped bar chart: one income bar and one expense bar per group (e.g. per month), with a
 * short label under each group. Callers set a content description summarising the values.
 */
public class PairedBarChartView extends View {

    private final Paint incomePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint expensePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private long[] income = new long[0];
    private long[] expense = new long[0];
    private String[] labels = new String[0];

    public PairedBarChartView(@NonNull Context context) {
        this(context, null);
    }

    public PairedBarChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        basePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        // SP-based size via applyDimension: honors the user's font scale and avoids the
        // deprecated resources.scaledDensity calculation.
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                getResources().getDisplayMetrics()));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(@NonNull long[] income, @NonNull long[] expense, @NonNull String[] labels) {
        if (income.length != expense.length || income.length != labels.length) {
            throw new IllegalArgumentException("Arrays must have the same length");
        }
        this.income = income.clone();
        this.expense = expense.clone();
        this.labels = labels.clone();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int groups = income.length;
        if (groups == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        incomePaint.setColor(ContextCompat.getColor(getContext(), R.color.income));
        expensePaint.setColor(ContextCompat.getColor(getContext(), R.color.expense));
        basePaint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant));
        textPaint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant));

        float labelHeight = textPaint.getTextSize() + 6 * density;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float baseline = getHeight() - getPaddingBottom() - labelHeight;
        float chartHeight = baseline - top;
        float width = right - left;
        if (width <= 0 || chartHeight <= 0) {
            return;
        }
        long max = 0;
        for (int i = 0; i < groups; i++) {
            max = Math.max(max, Math.max(income[i], expense[i]));
        }
        float slot = width / groups;
        float barWidth = Math.min(slot * 0.32f, 14 * density);
        float radius = 2 * density;
        for (int i = 0; i < groups; i++) {
            float center = left + slot * i + slot / 2f;
            if (max > 0) {
                drawBar(canvas, center - barWidth - density, barWidth, income[i], max, chartHeight, baseline, radius, incomePaint, density);
                drawBar(canvas, center + density, barWidth, expense[i], max, chartHeight, baseline, radius, expensePaint, density);
            }
            // Skip labels that would collide when there are many groups.
            int every = Math.max(1, (int) Math.ceil(textPaint.measureText("MMM") * 1.3f / slot));
            if (i % every == 0) {
                canvas.drawText(labels[i], center, getHeight() - getPaddingBottom() - 2 * density, textPaint);
            }
        }
        canvas.drawLine(left, baseline, right, baseline, basePaint);
    }

    private void drawBar(Canvas canvas, float x, float w, long value, long max, float chartHeight,
                         float baseline, float radius, Paint paint, float density) {
        if (value <= 0) {
            return;
        }
        float h = Math.max(2 * density, chartHeight * value / (float) max);
        bar.set(x, baseline - h, x + w, baseline);
        canvas.drawRoundRect(bar, radius, radius, paint);
    }
}
