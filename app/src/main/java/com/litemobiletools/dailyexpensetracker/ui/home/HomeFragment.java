package com.litemobiletools.dailyexpensetracker.ui.home;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.room.InvalidationTracker;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.repository.DashboardRepository;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.CategorySlice;
import com.litemobiletools.dailyexpensetracker.model.DashboardSnapshot;
import com.litemobiletools.dailyexpensetracker.ui.add.AddTransactionActivity;
import com.litemobiletools.dailyexpensetracker.ui.budgets.BudgetRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.insights.InsightsActivity;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionDetailsSheet;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.widget.DonutChartView;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.Calendar;
import java.util.List;
import java.util.Set;

/**
 * Dashboard backed by Room. Totals combine only transactions in the device currency. While the
 * screen is visible it observes the database and refreshes after any insert, edit or delete.
 */
public class HomeFragment extends Fragment {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InvalidationTracker.Observer observer;
    private boolean loadErrorShown;

    private TextView balance;
    private TextView income;
    private TextView expense;
    private TextView budgetSummary;
    private TextView budgetHint;
    private LinearProgressIndicator budgetProgress;
    private DonutChartView chart;
    private TextView chartTotal;
    private TextView chartEmpty;
    private LinearLayout legend;
    private LinearLayout transactions;
    private View emptyState;
    private View balanceCard;
    private View incomeCard;
    private View expenseCard;
    private View budgetCard;
    private View budgetHighlights;
    private LinearLayout budgetHighlightRows;
    private TextView insightsText;
    /** The currency the dashboard works in (the user's choice); overridden at every refresh. */
    private String workingCurrency = CurrencyFormatter.currentCode();
    private TextView currencyNote;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        balance = view.findViewById(R.id.home_balance);
        income = view.findViewById(R.id.home_income);
        expense = view.findViewById(R.id.home_expense);
        budgetSummary = view.findViewById(R.id.home_budget_summary);
        budgetHint = view.findViewById(R.id.home_budget_hint);
        budgetProgress = view.findViewById(R.id.home_budget_progress);
        chart = view.findViewById(R.id.home_chart);
        chartTotal = view.findViewById(R.id.home_chart_total);
        chartEmpty = view.findViewById(R.id.home_chart_empty);
        legend = view.findViewById(R.id.home_legend);
        transactions = view.findViewById(R.id.home_transactions);
        emptyState = view.findViewById(R.id.home_empty_state);
        balanceCard = view.findViewById(R.id.home_balance_card);
        incomeCard = view.findViewById(R.id.home_income_card);
        expenseCard = view.findViewById(R.id.home_expense_card);
        budgetCard = view.findViewById(R.id.home_budget_card);
        budgetHighlights = view.findViewById(R.id.home_budget_highlights);
        budgetHighlightRows = view.findViewById(R.id.home_budget_highlight_rows);
        insightsText = view.findViewById(R.id.home_insights_text);
        currencyNote = view.findViewById(R.id.home_currency_note);
        view.findViewById(R.id.home_insights_button).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), InsightsActivity.class)));

        ((TextView) view.findViewById(R.id.home_greeting)).setText(greetingForNow());
        ((TextView) view.findViewById(R.id.home_date)).setText(DateUtils.formatDateTime(
                requireContext(), System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        view.findViewById(R.id.home_add_fab).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddTransactionActivity.class)));

        // Zero state until the first load completes.
        render(DashboardSnapshot.empty());
    }

    @Override
    public void onStart() {
        super.onStart();
        // Changes can arrive from the Add screen; refresh on every start, then stay live.
        refresh();
        observer = new InvalidationTracker.Observer("transactions", "categories") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                // Called on a Room background thread.
                mainHandler.post(() -> {
                    if (isAdded() && getView() != null) {
                        refresh();
                    }
                });
            }
        };
        AppDatabase.getInstance(requireContext()).getInvalidationTracker().addObserver(observer);
    }

    @Override
    public void onStop() {
        if (observer != null) {
            AppDatabase.getInstance(requireContext()).getInvalidationTracker().removeObserver(observer);
            observer = null;
        }
        super.onStop();
    }

    private void refresh() {
        // Read the setting at refresh time so a change made in Settings applies on the next
        // screen build without restarting the app. The working currency only decides which
        // currency these totals are stated in; nothing stored is converted or rewritten.
        workingCurrency = AppPreferences.getCurrencyCode(requireContext());
        DashboardRepository.get(requireContext()).load(workingCurrency,
                getString(R.string.home_breakdown_other), new RepoCallback<DashboardSnapshot>() {
                    @Override
                    public void onSuccess(DashboardSnapshot result) {
                        if (getView() != null) {
                            loadErrorShown = false;
                            render(result);
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        View view = getView();
                        if (view != null && !loadErrorShown) {
                            loadErrorShown = true;
                            Snackbar.make(view, R.string.home_load_error, Snackbar.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @StringRes
    private static int greetingForNow() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 12) {
            return R.string.greeting_morning;
        }
        return hour < 18 ? R.string.greeting_afternoon : R.string.greeting_evening;
    }

    private void render(@NonNull DashboardSnapshot data) {
        bindSummary(data);
        bindCurrencyNote(data);
        bindBudget(data);
        bindBreakdown(data);
        bindTransactions(data);
        bindBudgetHighlights(data);
        bindInsights(data);
    }

    /**
     * Truthful disclosure: the totals are stated in the selected currency and are never
     * converted, so entries in other currencies are excluded from them. The note disappears
     * again as soon as every entry this month is in the selected currency.
     */
    private void bindCurrencyNote(@NonNull DashboardSnapshot data) {
        if (data.otherCurrencyCount <= 0) {
            currencyNote.setVisibility(View.GONE);
            currencyNote.setText("");
            return;
        }
        String currencyName;
        try {
            currencyName = java.util.Currency.getInstance(workingCurrency)
                    .getDisplayName(java.util.Locale.getDefault());
        } catch (IllegalArgumentException e) {
            currencyName = workingCurrency; // Never leave the note out because of a bad code.
        }
        currencyNote.setText(getResources().getQuantityString(
                R.plurals.home_other_currency_note, data.otherCurrencyCount,
                currencyName, data.otherCurrencyCount));
        currencyNote.setVisibility(View.VISIBLE);
    }

    private void bindBudgetHighlights(DashboardSnapshot data) {
        budgetHighlightRows.removeAllViews();
        budgetHighlights.setVisibility(data.budgetHighlights.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (BudgetProgress progress : data.budgetHighlights) {
            View row = inflater.inflate(R.layout.item_progress, budgetHighlightRows, false);
            row.setClickable(false);
            row.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_sm), 0, 0);
            BudgetRowBinder.bind(row, progress);
            budgetHighlightRows.addView(row);
        }
    }

    /** A short, factual summary from recorded data. The month so far is compared with all of last month, labelled as such. */
    private void bindInsights(DashboardSnapshot data) {
        if (data.monthExpenseMinor <= 0 && data.prevMonthExpenseMinor <= 0) {
            insightsText.setText(R.string.home_insights_empty);
            return;
        }
        StringBuilder text = new StringBuilder(getString(R.string.home_insights_compare,
                CurrencyFormatter.format(data.monthExpenseMinor, workingCurrency),
                CurrencyFormatter.format(data.prevMonthExpenseMinor, workingCurrency)));
        if (!data.breakdown.isEmpty()) {
            CategorySlice top = data.breakdown.get(0);
            text.append('\n').append(getString(R.string.home_insights_top, top.name,
                    CurrencyFormatter.format(top.amountMinor, workingCurrency)));
        }
        insightsText.setText(text);
    }

    private void bindSummary(DashboardSnapshot data) {
        String balanceText = CurrencyFormatter.format(data.totalBalanceMinor, workingCurrency);
        String incomeText = CurrencyFormatter.format(data.monthIncomeMinor, workingCurrency);
        String expenseText = CurrencyFormatter.format(data.monthExpenseMinor, workingCurrency);
        balance.setText(balanceText);
        income.setText(incomeText);
        expense.setText(expenseText);
        balanceCard.setContentDescription(getString(R.string.home_total_balance) + ", " + balanceText);
        incomeCard.setContentDescription(getString(R.string.home_income) + ", " + incomeText);
        expenseCard.setContentDescription(getString(R.string.home_expenses) + ", " + expenseText);
    }

    private void bindBudget(DashboardSnapshot data) {
        if (data.monthBudgetMinor <= 0) {
            budgetSummary.setText(CurrencyFormatter.format(data.monthExpenseMinor, workingCurrency));
            budgetProgress.setProgress(0);
            budgetHint.setText(R.string.home_budget_none);
        } else {
            long spent = data.monthExpenseMinor;
            long budget = data.monthBudgetMinor;
            int percent = (int) Math.min(100, spent * 100 / budget);
            budgetSummary.setText(getString(R.string.home_budget_summary,
                    CurrencyFormatter.format(spent, workingCurrency), CurrencyFormatter.format(budget, workingCurrency)));
            budgetProgress.setProgress(percent);
            budgetProgress.setIndicatorColor(ContextCompat.getColor(requireContext(),
                    spent >= budget ? R.color.expense : percent >= 80 ? R.color.warning : R.color.income));
            if (spent > budget) {
                budgetHint.setText(getString(R.string.home_budget_over,
                        CurrencyFormatter.format(spent - budget, workingCurrency)));
            } else {
                budgetHint.setText(getString(R.string.home_budget_hint, percent,
                        CurrencyFormatter.format(budget - spent, workingCurrency)));
            }
        }
        budgetCard.setContentDescription(getString(R.string.home_monthly_spending) + ", "
                + budgetSummary.getText() + ", " + budgetHint.getText());
    }

    private void bindBreakdown(DashboardSnapshot data) {
        legend.removeAllViews();
        List<CategorySlice> slices = data.breakdown;
        long total = 0;
        for (CategorySlice slice : slices) {
            total += slice.amountMinor;
        }

        long[] values = new long[slices.size()];
        int[] colors = new int[slices.size()];
        StringBuilder description = new StringBuilder();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < slices.size(); i++) {
            CategorySlice slice = slices.get(i);
            values[i] = slice.amountMinor;
            colors[i] = ContextCompat.getColor(requireContext(), slice.colorRes);
            int percent = total > 0 ? Math.round(100f * slice.amountMinor / total) : 0;

            View row = inflater.inflate(R.layout.item_legend, legend, false);
            row.findViewById(R.id.legend_dot).setBackgroundTintList(ColorStateList.valueOf(colors[i]));
            ((TextView) row.findViewById(R.id.legend_name)).setText(slice.name);
            ((TextView) row.findViewById(R.id.legend_value)).setText(getString(R.string.percent_value, percent));
            row.setFocusable(true);
            row.setContentDescription(getString(R.string.chart_slice_description, slice.name, percent));
            legend.addView(row);
            description.append(getString(R.string.chart_description_entry, slice.name, percent)).append(' ');
        }
        chart.setSlices(values, colors);
        chart.setContentDescription(getString(R.string.chart_description,
                total > 0 ? description.toString() : getString(R.string.home_breakdown_empty)));
        chartTotal.setText(CurrencyFormatter.format(total, workingCurrency));
        chartEmpty.setVisibility(total > 0 ? View.GONE : View.VISIBLE);
        legend.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
    }

    private void bindTransactions(DashboardSnapshot data) {
        transactions.removeAllViews();
        boolean hasItems = !data.recent.isEmpty();
        transactions.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(hasItems ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        long now = System.currentTimeMillis();
        for (TransactionRow item : data.recent) {
            View row = inflater.inflate(R.layout.item_transaction, transactions, false);
            TransactionRowBinder.bind(row, item, true, now);
            row.setOnClickListener(v -> TransactionDetailsSheet.show(getParentFragmentManager(), item.id));
            transactions.addView(row);
        }
    }

    @Override
    public void onDestroyView() {
        balance = null;
        income = null;
        expense = null;
        budgetSummary = null;
        budgetHint = null;
        budgetProgress = null;
        chart = null;
        chartTotal = null;
        chartEmpty = null;
        legend = null;
        transactions = null;
        emptyState = null;
        balanceCard = null;
        incomeCard = null;
        expenseCard = null;
        budgetCard = null;
        budgetHighlights = null;
        budgetHighlightRows = null;
        insightsText = null;
        super.onDestroyView();
    }
}
