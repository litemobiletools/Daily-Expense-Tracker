package com.litemobiletools.dailyexpensetracker.ui.goals;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.ui.common.ProgressBinder;
import com.litemobiletools.dailyexpensetracker.util.BudgetMath;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

/** Shows a goal's tracked progress in an {@code item_progress} row. Tracked savings only; not account balances. */
public final class GoalRowBinder {

    private GoalRowBinder() {
    }

    public static void bind(@NonNull View root, @NonNull GoalWithSaved item) {
        Context context = root.getContext();
        SavingsGoalEntity goal = item.goal;
        String saved = CurrencyFormatter.format(item.savedMinor, goal.currencyCode);
        String target = CurrencyFormatter.format(goal.targetMinor, goal.currencyCode);
        int percent = BudgetMath.percent(item.savedMinor, goal.targetMinor);
        int color = ContextCompat.getColor(context, goal.isCompleted ? R.color.income : R.color.det_primary);

        String status = goal.isCompleted ? context.getString(R.string.goal_completed) : null;
        long remaining = Math.max(0, goal.targetMinor - item.savedMinor);
        String right = goal.isCompleted ? null
                : context.getString(R.string.goal_remaining, CurrencyFormatter.format(remaining, goal.currencyCode));
        String left = context.getString(R.string.goal_percent, percent);
        if (goal.targetDate != null) {
            String date = DateUtils.formatDateTime(context, goal.targetDate,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
            left = left + " · " + context.getString(R.string.goal_due, date);
        }
        String description = context.getString(R.string.goal_row_description, goal.name, saved, target, percent,
                goal.isCompleted ? context.getString(R.string.goal_completed)
                        : context.getString(R.string.goal_remaining, CurrencyFormatter.format(remaining, goal.currencyCode)));
        ProgressBinder.bind(root, goal.name, status, ContextCompat.getColor(context, R.color.income),
                context.getString(R.string.goal_saved_of, saved, target), percent, color, left, right, description);
    }
}
