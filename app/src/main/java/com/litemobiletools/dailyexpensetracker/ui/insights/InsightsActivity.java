package com.litemobiletools.dailyexpensetracker.ui.insights;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.ads.AdSupport;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.InsightsRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.MonthInsights;
import com.litemobiletools.dailyexpensetracker.ui.budgets.BudgetRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.common.ProgressBinder;
import com.litemobiletools.dailyexpensetracker.ui.common.SecondaryActivity;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionDetailsSheet;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.widget.BarChartView;
import com.litemobiletools.dailyexpensetracker.util.BudgetMath;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.PeriodMath;

import java.util.TimeZone;

/**
 * Month-by-month spending insights. Everything shown is computed from recorded transactions in the
 * device currency; there are no forecasts and no advice.
 */
public class InsightsActivity extends SecondaryActivity {

    private static final String STATE_ANCHOR = "anchor";

    private final TimeZone zone = TimeZone.getDefault();
    private long anchor;
    private int generation;

    /**
     * Phase 10: finishing this read-only screen is the one natural, non-financial transition in
     * the app where an interstitial is allowed. It never shows when this session performed a
     * transaction action through a details sheet (including deletes) - any details interaction
     * suppresses the ad for the whole session.
     */
    private boolean detailsSheetUsed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initScreen(savedInstanceState, R.layout.activity_insights, R.string.insights_title);
        anchor = savedInstanceState != null
                ? savedInstanceState.getLong(STATE_ANCHOR, System.currentTimeMillis()) : System.currentTimeMillis();
        findViewById(R.id.insights_prev).setOnClickListener(v -> {
            anchor = PeriodMath.shift(BudgetPeriod.MONTHLY, anchor, -1, zone);
            load();
        });
        findViewById(R.id.insights_next).setOnClickListener(v -> {
            anchor = PeriodMath.shift(BudgetPeriod.MONTHLY, anchor, 1, zone);
            load();
        });

        // Both exit routes (toolbar arrow and system back) go through the same finish method; the
        // interstitial helper finishes normally whenever no ad is permitted or available.
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finishWithOptionalInterstitial());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishWithOptionalInterstitial();
            }
        });
    }

    /** Leaves the Insights screen, optionally after showing the capped interstitial. */
    private void finishWithOptionalInterstitial() {
        if (detailsSheetUsed) {
            finish();
            return;
        }
        AdSupport.showInterstitialIfPermitted(this, this::finish);
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_ANCHOR, anchor);
    }

    private void load() {
        final int gen = ++generation;
        long[] range = PeriodMath.range(BudgetPeriod.MONTHLY, anchor, zone, 0);
        ((TextView) findViewById(R.id.insights_month)).setText(DateUtils.formatDateTime(this, range[0],
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_MONTH_DAY | DateUtils.FORMAT_SHOW_YEAR));
        long[] current = PeriodMath.range(BudgetPeriod.MONTHLY, System.currentTimeMillis(), zone, 0);
        boolean atCurrent = range[0] >= current[0];
        findViewById(R.id.insights_next).setEnabled(!atCurrent);
        findViewById(R.id.insights_next).setAlpha(atCurrent ? 0.38f : 1f);

        InsightsRepository.get(this).load(AppPreferences.getCurrencyCode(this), anchor, new RepoCallback<MonthInsights>() {
            @Override
            public void onSuccess(MonthInsights insights) {
                if (isDestroyed() || gen != generation) {
                    return;
                }
                findViewById(R.id.insights_error).setVisibility(View.GONE);
                findViewById(R.id.insights_content).setVisibility(View.VISIBLE);
                render(insights);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed() && gen == generation) {
                    findViewById(R.id.insights_content).setVisibility(View.GONE);
                    findViewById(R.id.insights_error).setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void render(@NonNull MonthInsights in) {
        String currency = AppPreferences.getCurrencyCode(this);
        String income = CurrencyFormatter.format(in.incomeMinor, currency);
        String expense = CurrencyFormatter.format(in.expenseMinor, currency);
        ((TextView) findViewById(R.id.insights_income)).setText(income);
        ((TextView) findViewById(R.id.insights_expense)).setText(expense);
        findViewById(R.id.insights_income_block).setContentDescription(getString(R.string.insights_income) + ", " + income);
        findViewById(R.id.insights_expense_block).setContentDescription(getString(R.string.insights_expenses) + ", " + expense);

        TextView vs = findViewById(R.id.insights_vs);
        String prev = CurrencyFormatter.format(in.prevExpenseMinor, currency);
        if (in.expenseChangePercent == null) {
            vs.setText(R.string.insights_vs_none);
        } else if (in.expenseChangePercent > 0) {
            vs.setText(getString(R.string.insights_vs_up, in.expenseChangePercent, prev));
        } else if (in.expenseChangePercent < 0) {
            vs.setText(getString(R.string.insights_vs_down, -in.expenseChangePercent, prev));
        } else {
            vs.setText(getString(R.string.insights_vs_same, prev));
        }

        if (in.expenseChangePercent != null && in.daysCounted < PeriodMath.daysBetween(
                in.monthStart, in.monthEndExclusive, zone) && in.daysCounted > 0) {
            vs.setText(getString(R.string.insights_vs_with_note, vs.getText(),
                    getString(R.string.insights_in_progress)));
        }

        TextView average = findViewById(R.id.insights_average);
        average.setText(in.daysCounted <= 0 ? getString(R.string.insights_average_none)
                : getString(R.string.insights_average_value,
                        CurrencyFormatter.format(in.averageDailyExpenseMinor, currency), in.daysCounted));

        TextView savings = findViewById(R.id.insights_savings);
        if (in.savingsRatePercent == null) {
            savings.setText(R.string.insights_savings_none);
        } else if (in.savingsRatePercent >= 0) {
            savings.setText(getString(R.string.insights_savings_value, in.savingsRatePercent));
        } else {
            savings.setText(getString(R.string.insights_savings_negative, -in.savingsRatePercent));
        }

        renderChart(in, currency);
        renderCategories(in, currency);
        renderLargest(in);
        renderDays(in, currency);
        renderBudgets(in);
    }

    private void renderChart(@NonNull MonthInsights in, String currency) {
        BarChartView chart = findViewById(R.id.insights_chart);
        TextView empty = findViewById(R.id.insights_chart_empty);
        if (in.expenseMinor <= 0) {
            chart.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        chart.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        chart.setValues(in.dailyExpenseMinor);
        MonthInsights.DayTotal top = in.highestDays.get(0);
        chart.setContentDescription(getString(R.string.insights_daily_chart_description,
                ((TextView) findViewById(R.id.insights_month)).getText(),
                DateUtils.formatDateTime(this, top.dayStart, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH),
                CurrencyFormatter.format(top.amountMinor, currency),
                CurrencyFormatter.format(in.expenseMinor, currency)));
    }

    private void renderCategories(@NonNull MonthInsights in, String currency) {
        LinearLayout container = findViewById(R.id.insights_categories);
        container.removeAllViews();
        findViewById(R.id.insights_categories_card).setVisibility(in.topCategories.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        int color = ContextCompat.getColor(this, R.color.det_primary);
        for (CategoryTotal total : in.topCategories) {
            int percent = in.expenseMinor > 0 ? Math.round(100f * total.totalMinor / in.expenseMinor) : 0;
            String amount = CurrencyFormatter.format(total.totalMinor, currency);
            View row = inflater.inflate(R.layout.item_progress, container, false);
            row.setClickable(false);
            ProgressBinder.bind(row, total.categoryName, null, color, amount, percent, color,
                    getString(R.string.goal_percent, percent), null,
                    getString(R.string.insights_category_line, total.categoryName, amount, percent));
            container.addView(row);
        }
    }

    private void renderLargest(@NonNull MonthInsights in) {
        LinearLayout container = findViewById(R.id.insights_largest);
        container.removeAllViews();
        findViewById(R.id.insights_largest_card).setVisibility(in.largestExpenses.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        long now = System.currentTimeMillis();
        for (TransactionRow row : in.largestExpenses) {
            View view = inflater.inflate(R.layout.item_transaction, container, false);
            TransactionRowBinder.bind(view, row, true, now);
            view.setOnClickListener(v -> {
                // Touched a record's details (a delete can be offered there): no interstitial for
                // the remainder of this session, per the financial-action safety rule.
                detailsSheetUsed = true;
                final long id = row.id;
                TransactionDetailsSheet.show(getSupportFragmentManager(), id);
            });
            container.addView(view);
        }
    }

    private void renderDays(@NonNull MonthInsights in, String currency) {
        LinearLayout container = findViewById(R.id.insights_days);
        container.removeAllViews();
        findViewById(R.id.insights_days_card).setVisibility(in.highestDays.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (MonthInsights.DayTotal day : in.highestDays) {
            View line = inflater.inflate(R.layout.item_insight_line, container, false);
            String date = DateUtils.formatDateTime(this, day.dayStart,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
            String amount = CurrencyFormatter.format(day.amountMinor, currency);
            ((TextView) line.findViewById(R.id.line_left)).setText(date);
            TextView right = line.findViewById(R.id.line_right);
            right.setText(amount);
            right.setTextColor(ContextCompat.getColor(this, R.color.expense));
            line.setContentDescription(getString(R.string.insights_day_line, date, amount));
            container.addView(line);
        }
    }

    private void renderBudgets(@NonNull MonthInsights in) {
        LinearLayout container = findViewById(R.id.insights_budgets);
        container.removeAllViews();
        TextView text = findViewById(R.id.insights_budget_text);
        if (in.monthlyBudgets.isEmpty()) {
            text.setText(R.string.insights_budgets_none);
            text.setVisibility(View.VISIBLE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        int flagged = 0;
        for (BudgetProgress progress : in.monthlyBudgets) {
            BudgetMath.Status status = progress.status();
            if (status == BudgetMath.Status.OK || status == BudgetMath.Status.NONE) {
                continue;
            }
            flagged++;
            View row = inflater.inflate(R.layout.item_progress, container, false);
            row.setClickable(false);
            BudgetRowBinder.bind(row, progress);
            container.addView(row);
        }
        if (flagged == 0) {
            text.setText(R.string.insights_budgets_ok);
            text.setVisibility(View.VISIBLE);
        } else {
            text.setVisibility(View.GONE);
        }
    }
}
