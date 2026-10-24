package com.litemobiletools.dailyexpensetracker.ui.common;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.litemobiletools.dailyexpensetracker.R;

/** Fills an {@code item_progress} row (title, status, amounts, bar, footer) used by budgets, goals and dashboard. */
public final class ProgressBinder {

    private ProgressBinder() {
    }

    public static void bind(@NonNull View root, @NonNull String title, @Nullable String status,
                            @ColorInt int statusColor, @NonNull String amounts, int progressPercent,
                            @ColorInt int indicatorColor, @Nullable String left, @Nullable String right,
                            @NonNull String contentDescription) {
        ((TextView) root.findViewById(R.id.progress_title)).setText(title);
        TextView statusView = root.findViewById(R.id.progress_status);
        statusView.setText(status == null ? "" : status);
        statusView.setTextColor(statusColor);
        statusView.setVisibility(status == null || status.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) root.findViewById(R.id.progress_amounts)).setText(amounts);
        LinearProgressIndicator bar = root.findViewById(R.id.progress_bar);
        bar.setIndicatorColor(indicatorColor);
        bar.setProgressCompat(Math.max(0, Math.min(100, progressPercent)), false);
        ((TextView) root.findViewById(R.id.progress_left)).setText(left == null ? "" : left);
        ((TextView) root.findViewById(R.id.progress_right)).setText(right == null ? "" : right);
        root.setContentDescription(contentDescription);
    }
}
