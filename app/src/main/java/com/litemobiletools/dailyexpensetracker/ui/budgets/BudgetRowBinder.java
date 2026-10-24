package com.litemobiletools.dailyexpensetracker.ui.budgets;

import android.content.Context;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.ui.common.ProgressBinder;
import com.litemobiletools.dailyexpensetracker.util.BudgetMath;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

/**
 * Shows one {@link BudgetProgress} in an {@code item_progress} row. Status is always conveyed in
 * words as well as colour, and the whole row has one spoken description.
 */
public final class BudgetRowBinder {

    private BudgetRowBinder() {
    }

    public static void bind(@NonNull View root, @NonNull BudgetProgress progress) {
        Context context = root.getContext();
        String currency = progress.budget.currencyCode;
        String title = progress.isOverall() ? context.getString(R.string.budgets_overall) : progress.categoryName;
        String spent = CurrencyFormatter.format(progress.spentMinor, currency);
        String limit = CurrencyFormatter.format(progress.limitMinor(), currency);
        BudgetMath.Status status = progress.status();

        String statusText;
        int colorRes;
        String right;
        switch (status) {
            case EXCEEDED:
                statusText = context.getString(R.string.budget_status_exceeded,
                        CurrencyFormatter.format(-progress.remainingMinor(), currency));
                colorRes = R.color.expense;
                right = null;
                break;
            case REACHED:
                statusText = context.getString(R.string.budget_status_reached);
                colorRes = R.color.expense;
                right = null;
                break;
            case WARNING:
                statusText = context.getString(R.string.budget_status_warning);
                colorRes = R.color.warning;
                right = context.getString(R.string.budget_remaining,
                        CurrencyFormatter.format(progress.remainingMinor(), currency));
                break;
            default:
                statusText = context.getString(R.string.budget_status_ok);
                colorRes = R.color.income;
                right = context.getString(R.string.budget_remaining,
                        CurrencyFormatter.format(progress.remainingMinor(), currency));
                break;
        }
        @ColorInt int color = ContextCompat.getColor(context, colorRes);
        int percent = progress.percent();
        ProgressBinder.bind(root, title, statusText, color,
                context.getString(R.string.budget_spent_of, spent, limit), percent, color,
                context.getString(R.string.budget_percent_used, percent), right,
                context.getString(R.string.budget_row_description, title, spent, limit, percent, statusText));
    }
}
