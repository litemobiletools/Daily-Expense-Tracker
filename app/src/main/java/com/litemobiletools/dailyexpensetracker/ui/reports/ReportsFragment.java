package com.litemobiletools.dailyexpensetracker.ui.reports;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.room.InvalidationTracker;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.ReportRepository;
import com.litemobiletools.dailyexpensetracker.export.ExportActions;
import com.litemobiletools.dailyexpensetracker.export.ExportRequest;
import com.litemobiletools.dailyexpensetracker.export.ExportRunner;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.budgets.BudgetRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.common.ProgressBinder;
import com.litemobiletools.dailyexpensetracker.ui.main.MainActivity;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionDetailsSheet;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionRowBinder;
import com.litemobiletools.dailyexpensetracker.ui.widget.BarChartView;
import com.litemobiletools.dailyexpensetracker.ui.widget.DonutChartView;
import com.litemobiletools.dailyexpensetracker.ui.widget.PairedBarChartView;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;
import com.litemobiletools.dailyexpensetracker.util.ReportPeriods;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reports tab: totals, previous-period comparison, trends, category breakdowns with drill-down,
 * largest transactions and budget versus actual for a chosen period, plus CSV/PDF export through the
 * Storage Access Framework.
 */
public class ReportsFragment extends Fragment {

    private static final String STATE_PRESET = "preset";
    private static final String STATE_CUSTOM_START = "custom_start";
    private static final String STATE_CUSTOM_END = "custom_end";
    private static final String STATE_SORT_NAME = "sort_name";
    private static final String STATE_PENDING = "pending_export";
    private static final int[] SLICE_COLORS = {R.color.chart_1, R.color.chart_2, R.color.chart_3,
            R.color.chart_4, R.color.chart_5};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TimeZone zone = TimeZone.getDefault();
    private InvalidationTracker.Observer observer;
    private int generation;

    private ReportPreset preset = ReportPreset.THIS_MONTH;
    private long customStart = -1;
    private long customEnd = -1;
    private boolean sortByName;
    @Nullable private ReportData data;
    @Nullable private ExportRequest pendingExport;
    @Nullable private AlertDialog progressDialog;

    private final ActivityResultLauncher<String> csvLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/csv"), this::onDocumentChosen);
    private final ActivityResultLauncher<String> pdfLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"), this::onDocumentChosen);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reports, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            preset = ReportPreset.valueOf(savedInstanceState.getString(STATE_PRESET, ReportPreset.THIS_MONTH.name()));
            customStart = savedInstanceState.getLong(STATE_CUSTOM_START, -1);
            customEnd = savedInstanceState.getLong(STATE_CUSTOM_END, -1);
            sortByName = savedInstanceState.getBoolean(STATE_SORT_NAME);
            pendingExport = (ExportRequest) savedInstanceState.getSerializable(STATE_PENDING);
            if (preset == ReportPreset.CUSTOM && (customStart < 0 || customEnd < 0)) {
                preset = ReportPreset.THIS_MONTH;
            }
        }

        ChipGroup presets = view.findViewById(R.id.report_presets);
        presets.check(chipFor(preset));
        presets.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty() || ids.get(0) == R.id.report_chip_custom) {
                return; // custom is handled by its click listener
            }
            ReportPreset selected = presetFor(ids.get(0));
            if (selected != preset) {
                preset = selected;
                load();
            }
        });
        view.findViewById(R.id.report_chip_custom).setOnClickListener(v -> pickCustomRange());

        ChipGroup sort = view.findViewById(R.id.report_sort);
        sort.check(sortByName ? R.id.report_sort_name : R.id.report_sort_amount);
        sort.setOnCheckedStateChangeListener((group, ids) -> {
            sortByName = !ids.isEmpty() && ids.get(0) == R.id.report_sort_name;
            if (data != null) {
                renderExpenseCategories(data);
            }
        });

        view.findViewById(R.id.report_export).setOnClickListener(v -> {
            ReportRange range = currentRange();
            ExportOptionsSheet.show(getChildFragmentManager(), range, AppPreferences.getCurrencyCode(requireContext()),
                    range.preset == ReportPreset.THIS_WEEK || range.preset == ReportPreset.THIS_MONTH
                            || range.preset == ReportPreset.PREVIOUS_MONTH);
        });
        getChildFragmentManager().setFragmentResultListener(ExportOptionsSheet.RESULT_KEY, getViewLifecycleOwner(),
                (key, result) -> {
                    ExportRequest request = (ExportRequest) result.getSerializable(ExportOptionsSheet.RESULT_REQUEST);
                    if (request != null) {
                        pendingExport = request;
                        String name = request.suggestedName(zone);
                        (request.kind == ExportRequest.Kind.PDF ? pdfLauncher : csvLauncher).launch(name);
                    }
                });
        view.findViewById(R.id.report_view_transactions).setOnClickListener(v -> {
            ReportRange range = currentRange();
            TransactionFilter filter = new TransactionFilter();
            filter.startMillis = range.start;
            filter.endExclusiveMillis = range.endExclusive;
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openTransactions(filter);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        load();
        observer = new InvalidationTracker.Observer("transactions", "categories", "budgets") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                mainHandler.post(() -> {
                    if (isAdded() && getView() != null) {
                        load();
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

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PRESET, preset.name());
        outState.putLong(STATE_CUSTOM_START, customStart);
        outState.putLong(STATE_CUSTOM_END, customEnd);
        outState.putBoolean(STATE_SORT_NAME, sortByName);
        outState.putSerializable(STATE_PENDING, pendingExport);
    }

    @Override
    public void onDestroyView() {
        generation++;
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        super.onDestroyView();
    }

    // ---- Period selection ------------------------------------------------------------

    @NonNull
    private ReportRange currentRange() {
        if (preset == ReportPreset.CUSTOM) {
            return ReportPeriods.custom(customStart, customEnd, zone);
        }
        return ReportPeriods.resolve(preset, System.currentTimeMillis(), zone, BudgetRepository.firstDayOfWeek());
    }

    private void pickCustomRange() {
        ReportRange current = currentRange();
        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(R.string.reports_custom)
                .setSelection(new Pair<>(DateRanges.pickerUtcFromLocal(current.start, zone),
                        DateRanges.pickerUtcFromLocal(current.lastInstant(), zone)))
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection.first == null || selection.second == null) {
                restoreChip();
                return;
            }
            long start = DateRanges.localStartOfPickedDay(selection.first, zone);
            long end = DateRanges.localStartOfPickedDay(selection.second, zone);
            if (start > end) {
                Toast.makeText(requireContext(), R.string.reports_custom_invalid, Toast.LENGTH_LONG).show();
                restoreChip();
                return;
            }
            customStart = start;
            customEnd = end;
            preset = ReportPreset.CUSTOM;
            load();
        });
        picker.addOnNegativeButtonClickListener(v -> restoreChip());
        picker.addOnCancelListener(d -> restoreChip());
        picker.show(getChildFragmentManager(), "report_range");
    }

    /** Puts the chip selection back to the active preset after a cancelled custom pick. */
    private void restoreChip() {
        View view = getView();
        if (view != null) {
            ((ChipGroup) view.findViewById(R.id.report_presets)).check(chipFor(preset));
        }
    }

    @IdRes
    private static int chipFor(ReportPreset p) {
        switch (p) {
            case THIS_WEEK:
                return R.id.report_chip_week;
            case PREVIOUS_MONTH:
                return R.id.report_chip_prev_month;
            case LAST_30_DAYS:
                return R.id.report_chip_30;
            case LAST_90_DAYS:
                return R.id.report_chip_90;
            case THIS_YEAR:
                return R.id.report_chip_year;
            case CUSTOM:
                return R.id.report_chip_custom;
            default:
                return R.id.report_chip_month;
        }
    }

    private static ReportPreset presetFor(@IdRes int chip) {
        if (chip == R.id.report_chip_week) {
            return ReportPreset.THIS_WEEK;
        } else if (chip == R.id.report_chip_prev_month) {
            return ReportPreset.PREVIOUS_MONTH;
        } else if (chip == R.id.report_chip_30) {
            return ReportPreset.LAST_30_DAYS;
        } else if (chip == R.id.report_chip_90) {
            return ReportPreset.LAST_90_DAYS;
        } else if (chip == R.id.report_chip_year) {
            return ReportPreset.THIS_YEAR;
        }
        return ReportPreset.THIS_MONTH;
    }

    // ---- Loading ---------------------------------------------------------------------

    private void load() {
        View view = getView();
        if (view == null) {
            return;
        }
        final int gen = ++generation;
        ReportRange range = currentRange();
        String currency = AppPreferences.getCurrencyCode(requireContext());
        ((TextView) view.findViewById(R.id.report_period)).setText(periodText(range));
        ((TextView) view.findViewById(R.id.report_currency)).setText(getString(R.string.reports_currency_note, currency));
        view.findViewById(R.id.report_period_block).setContentDescription(periodText(range) + ", "
                + getString(R.string.reports_currency_note, currency));
        view.findViewById(R.id.report_loading).setVisibility(View.VISIBLE);

        ReportRepository.get(requireContext()).load(currency, range, new RepoCallback<ReportData>() {
            @Override
            public void onSuccess(ReportData result) {
                View root = getView();
                if (gen != generation || root == null) {
                    return;
                }
                data = result;
                root.findViewById(R.id.report_loading).setVisibility(View.GONE);
                root.findViewById(R.id.report_error).setVisibility(View.GONE);
                root.findViewById(R.id.report_content).setVisibility(View.VISIBLE);
                render(result);
            }

            @Override
            public void onError(@NonNull Exception error) {
                View root = getView();
                if (gen != generation || root == null) {
                    return;
                }
                root.findViewById(R.id.report_loading).setVisibility(View.GONE);
                root.findViewById(R.id.report_content).setVisibility(View.GONE);
                root.findViewById(R.id.report_error).setVisibility(View.VISIBLE);
            }
        });
    }

    private String periodText(ReportRange range) {
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH;
        return getString(R.string.reports_period_value,
                DateUtils.formatDateTime(requireContext(), range.start, flags),
                DateUtils.formatDateTime(requireContext(), range.lastInstant(), flags));
    }

    // ---- Rendering -------------------------------------------------------------------

    private void render(@NonNull ReportData d) {
        View view = requireView();
        String currency = d.currencyCode;
        String income = CurrencyFormatter.format(d.incomeMinor, currency);
        String expense = CurrencyFormatter.format(d.expenseMinor, currency);
        long net = d.netMinor();
        String netText = net < 0 ? "−" +CurrencyFormatter.format(-net, currency) : CurrencyFormatter.format(net, currency);
        line(view.findViewById(R.id.report_income_line), getString(R.string.reports_income), income, R.color.income);
        line(view.findViewById(R.id.report_expense_line), getString(R.string.reports_expenses), expense, R.color.expense);
        line(view.findViewById(R.id.report_net_line), getString(R.string.reports_net), netText,
                net < 0 ? R.color.expense : R.color.income);
        view.findViewById(R.id.report_summary_block).setContentDescription(getString(R.string.reports_summary_description,
                getString(R.string.reports_income), income, getString(R.string.reports_expenses), expense,
                getString(R.string.reports_net), netText));
        view.findViewById(R.id.report_empty).setVisibility(d.isEmpty() ? View.VISIBLE : View.GONE);

        ((TextView) view.findViewById(R.id.report_compare_title)).setText(getString(R.string.reports_previous_title,
                DateUtils.formatDateTime(requireContext(), d.previousRange.start, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH),
                DateUtils.formatDateTime(requireContext(), d.previousRange.lastInstant(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH)));
        ((TextView) view.findViewById(R.id.report_compare)).setText(getString(R.string.reports_compare_income_expenses,
                changeLine(getString(R.string.reports_income), d.incomeMinor, d.prevIncomeMinor, d.incomeChangePercent, currency),
                changeLine(getString(R.string.reports_expenses), d.expenseMinor, d.prevExpenseMinor,
                        d.expenseChangePercent, currency)));
        ((TextView) view.findViewById(R.id.report_average)).setText(d.daysCounted <= 0
                ? getString(R.string.reports_daily_average_none)
                : getString(R.string.reports_daily_average, CurrencyFormatter.format(d.averageDailyExpenseMinor, currency), d.daysCounted));

        List<String> notes = new ArrayList<>();
        if (ReportPeriods.inProgress(d.range, System.currentTimeMillis())) {
            notes.add(getString(R.string.reports_in_progress));
        }
        if (d.otherCurrencyCount > 0) {
            notes.add(getResources().getQuantityString(R.plurals.reports_other_currencies,
                    d.otherCurrencyCount, d.otherCurrencyCount));
        }
        TextView notesView = view.findViewById(R.id.report_notes);
        notesView.setText(android.text.TextUtils.join(" ", notes));
        notesView.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);

        renderTrend(d);
        renderExpenseCategories(d);
        renderCategories(view.findViewById(R.id.report_income_categories),
                view.findViewById(R.id.report_income_categories_empty), d.incomeByCategory, d.incomeMinor,
                TransactionType.INCOME, d, false);
        renderLargest(view.findViewById(R.id.report_largest_expenses_card),
                view.findViewById(R.id.report_largest_expenses), d.largestExpenses);
        renderLargest(view.findViewById(R.id.report_largest_income_card),
                view.findViewById(R.id.report_largest_income), d.largestIncome);
        renderBudgets(d);
    }

    private void line(View line, String label, String value, int colorRes) {
        ((TextView) line.findViewById(R.id.line_left)).setText(label);
        TextView right = line.findViewById(R.id.line_right);
        right.setText(value);
        right.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        line.setFocusable(false);
    }

    /** A comparison line that never shows a percentage against a zero baseline. */
    private String changeLine(String label, long now, long before, @Nullable Integer percent, String currency) {
        String previous = CurrencyFormatter.format(before, currency);
        if (percent == null) {
            return getString(R.string.reports_change_none, label, CurrencyFormatter.format(now, currency));
        }
        if (percent > 0) {
            return getString(R.string.reports_change_up, label, percent, previous);
        }
        if (percent < 0) {
            return getString(R.string.reports_change_down, label, -percent, previous);
        }
        return getString(R.string.reports_change_same, label, previous);
    }

    private void renderTrend(ReportData d) {
        View view = requireView();
        TextView title = view.findViewById(R.id.report_trend_title);
        BarChartView daily = view.findViewById(R.id.report_daily_chart);
        PairedBarChartView monthly = view.findViewById(R.id.report_monthly_chart);
        View legend = view.findViewById(R.id.report_monthly_legend);
        TextView empty = view.findViewById(R.id.report_trend_empty);
        String currency = d.currencyCode;

        if (d.dailyExpenseMinor != null && d.dayBoundaries != null) {
            title.setText(R.string.reports_trend_daily);
            monthly.setVisibility(View.GONE);
            legend.setVisibility(View.GONE);
            boolean hasData = d.expenseMinor > 0;
            daily.setVisibility(hasData ? View.VISIBLE : View.GONE);
            empty.setVisibility(hasData ? View.GONE : View.VISIBLE);
            if (!hasData) {
                return;
            }
            long[] values = d.dailyExpenseMinor;
            int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR;
            int last = values.length - 1;
            daily.setValues(values);
            daily.setAxisLabels(DateUtils.formatDateTime(requireContext(), d.dayBoundaries[0], flags),
                    DateUtils.formatDateTime(requireContext(), d.dayBoundaries[(values.length + 1) / 2 - 1], flags),
                    DateUtils.formatDateTime(requireContext(), d.dayBoundaries[last], flags));
            int maxIndex = 0;
            for (int i = 1; i < values.length; i++) {
                if (values[i] > values[maxIndex]) {
                    maxIndex = i;
                }
            }
            daily.setContentDescription(getString(R.string.reports_trend_daily_description,
                    DateUtils.formatDateTime(requireContext(), d.dayBoundaries[maxIndex], flags),
                    CurrencyFormatter.format(values[maxIndex], currency), CurrencyFormatter.format(d.expenseMinor, currency)));
        } else if (d.monthly != null) {
            title.setText(R.string.reports_trend_monthly);
            daily.setVisibility(View.GONE);
            boolean hasData = !d.isEmpty();
            monthly.setVisibility(hasData ? View.VISIBLE : View.GONE);
            legend.setVisibility(hasData ? View.VISIBLE : View.GONE);
            empty.setVisibility(hasData ? View.GONE : View.VISIBLE);
            if (!hasData) {
                return;
            }
            int n = d.monthly.size();
            long[] in = new long[n];
            long[] out = new long[n];
            String[] labels = new String[n];
            SimpleDateFormat month = new SimpleDateFormat("MMM", Locale.getDefault());
            month.setTimeZone(zone);
            StringBuilder description = new StringBuilder();
            for (int i = 0; i < n; i++) {
                ReportData.MonthTotal m = d.monthly.get(i);
                in[i] = m.incomeMinor;
                out[i] = m.expenseMinor;
                labels[i] = month.format(new Date(m.monthStart));
                description.append(getString(R.string.reports_trend_month_entry, labels[i],
                        CurrencyFormatter.format(m.incomeMinor, currency),
                        CurrencyFormatter.format(m.expenseMinor, currency))).append(' ');
            }
            monthly.setData(in, out, labels);
            monthly.setContentDescription(getString(R.string.reports_trend_monthly_description, description.toString().trim()));
        }
    }

    private void renderExpenseCategories(ReportData d) {
        View view = requireView();
        renderCategories(view.findViewById(R.id.report_expense_categories),
                view.findViewById(R.id.report_expense_categories_empty), d.expenseByCategory, d.expenseMinor,
                TransactionType.EXPENSE, d, sortByName);
        DonutChartView donut = view.findViewById(R.id.report_donut);
        List<CategoryTotal> totals = d.expenseByCategory; // amount order for the chart
        int n = Math.min(totals.size(), SLICE_COLORS.length);
        long[] values = new long[n];
        int[] colors = new int[n];
        StringBuilder description = new StringBuilder();
        long shown = 0;
        for (int i = 0; i < n; i++) {
            values[i] = totals.get(i).totalMinor;
            shown += values[i];
            colors[i] = ContextCompat.getColor(requireContext(), SLICE_COLORS[i]);
            description.append(getString(R.string.chart_description_entry, totals.get(i).categoryName,
                    percentOf(totals.get(i).totalMinor, d.expenseMinor))).append(' ');
        }
        if (totals.size() > n && n > 0) {
            values[n - 1] += d.expenseMinor - shown; // fold the rest into the last slice
        }
        donut.setSlices(values, colors);
        donut.setVisibility(totals.isEmpty() ? View.GONE : View.VISIBLE);
        donut.setContentDescription(getString(R.string.chart_description, description.toString().trim()));
    }

    private void renderCategories(LinearLayout container, View empty, List<CategoryTotal> source, long typeTotal,
                                  TransactionType type, ReportData d, boolean byName) {
        container.removeAllViews();
        empty.setVisibility(source.isEmpty() ? View.VISIBLE : View.GONE);
        List<CategoryTotal> totals = new ArrayList<>(source);
        if (byName) {
            Collections.sort(totals, (a, b) -> a.categoryName.compareToIgnoreCase(b.categoryName));
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int color = ContextCompat.getColor(requireContext(), type == TransactionType.INCOME ? R.color.income : R.color.det_primary);
        String typeLabel = getString(type == TransactionType.INCOME ? R.string.reports_income : R.string.reports_expenses)
                .toLowerCase(Locale.getDefault());
        for (CategoryTotal total : totals) {
            int percent = percentOf(total.totalMinor, typeTotal);
            String amount = CurrencyFormatter.format(total.totalMinor, d.currencyCode);
            String count = getResources().getQuantityString(R.plurals.reports_transaction_count,
                    total.transactionCount, total.transactionCount);
            View row = inflater.inflate(R.layout.item_progress, container, false);
            ProgressBinder.bind(row, total.categoryName, null, color,
                    getString(R.string.reports_category_amounts, amount, count), percent, color,
                    getString(R.string.reports_category_share, percent, typeLabel), null,
                    getString(R.string.reports_category_description, total.categoryName, amount, count, percent));
            final long id = total.categoryId;
            final String name = total.categoryName;
            row.setOnClickListener(v -> CategoryReportSheet.show(getChildFragmentManager(), id, name, type,
                    d.range, d.currencyCode));
            container.addView(row);
        }
    }

    private static int percentOf(long part, long whole) {
        return whole > 0 ? Math.round(100f * part / whole) : 0;
    }

    private void renderLargest(View card, LinearLayout container, List<TransactionRow> rows) {
        container.removeAllViews();
        card.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        long now = System.currentTimeMillis();
        for (TransactionRow row : rows) {
            View item = inflater.inflate(R.layout.item_transaction, container, false);
            TransactionRowBinder.bind(item, row, true, now);
            final long id = row.id;
            item.setOnClickListener(v -> TransactionDetailsSheet.show(getChildFragmentManager(), id));
            container.addView(item);
        }
    }

    private void renderBudgets(ReportData d) {
        View view = requireView();
        LinearLayout container = view.findViewById(R.id.report_budgets);
        TextView text = view.findViewById(R.id.report_budgets_text);
        container.removeAllViews();
        if (d.budgets == null) {
            text.setText(R.string.reports_budgets_not_applicable);
            text.setVisibility(View.VISIBLE);
            return;
        }
        if (d.budgets.isEmpty()) {
            text.setText(R.string.reports_budgets_none);
            text.setVisibility(View.VISIBLE);
            return;
        }
        text.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (BudgetProgress progress : d.budgets) {
            View row = inflater.inflate(R.layout.item_progress, container, false);
            row.setClickable(false);
            BudgetRowBinder.bind(row, progress);
            container.addView(row);
        }
    }

    // ---- Export ----------------------------------------------------------------------

    private void onDocumentChosen(@Nullable Uri uri) {
        ExportRequest request = pendingExport;
        pendingExport = null;
        if (uri == null || request == null) {
            return; // the user cancelled the save dialog: nothing to do, nothing was written
        }
        showProgress();
        ExportRunner.run(requireContext(), request, uri, new ExportRunner.Callback() {
            @Override
            public void onSuccess(@NonNull ExportRunner.Result result) {
                hideProgress();
                if (isAdded() && getActivity() != null) {
                    showDone(result);
                } else if (getContext() != null) {
                    Toast.makeText(getContext().getApplicationContext(), R.string.export_done_title, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                hideProgress();
                if (isAdded() && getActivity() != null) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.export_failed_title)
                            .setMessage(R.string.export_failed)
                            .setPositiveButton(R.string.action_ok, null)
                            .show();
                }
            }
        });
    }

    private void showProgress() {
        LinearLayout box = new LinearLayout(requireContext());
        int pad = getResources().getDimensionPixelSize(R.dimen.space_xl);
        box.setPadding(pad, pad / 2, pad, 0);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // Inflate against the real parent so the row's layout parameters resolve correctly.
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.item_insight_line, box, false);
        CircularProgressIndicator indicator = new CircularProgressIndicator(requireContext());
        indicator.setIndeterminate(true);
        box.addView(indicator);
        ((TextView) content.findViewById(R.id.line_left)).setText(R.string.export_progress);
        content.setPadding(pad / 2, 0, 0, 0);
        box.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(box)
                .setCancelable(false)
                .show();
    }

    private void hideProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private void showDone(@NonNull ExportRunner.Result result) {
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_export_done, null);
        String message;
        if (result.kind == ExportRequest.Kind.PDF) {
            message = getString(R.string.export_done_pdf, (int) result.count);
        } else if (result.kind == ExportRequest.Kind.TRANSACTIONS_CSV) {
            message = getResources().getQuantityString(R.plurals.export_done_transactions,
                    (int) result.count, (int) result.count);
        } else {
            message = getResources().getQuantityString(R.plurals.export_done_rows,
                    (int) result.count, (int) result.count);
        }
        ((TextView) content.findViewById(R.id.export_done_message)).setText(message);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.export_done_title)
                .setView(content)
                .setNegativeButton(R.string.export_close, null)
                .show();
        content.findViewById(R.id.export_done_share).setOnClickListener(v -> {
            dialog.dismiss();
            ExportActions.share(requireActivity(), result.uri, result.mimeType);
        });
        content.findViewById(R.id.export_done_open).setOnClickListener(v -> {
            dialog.dismiss();
            ExportActions.open(requireActivity(), result.uri, result.mimeType);
        });
        View print = content.findViewById(R.id.export_done_print);
        print.setVisibility(result.kind == ExportRequest.Kind.PDF ? View.VISIBLE : View.GONE);
        print.setOnClickListener(v -> {
            dialog.dismiss();
            ExportActions.print(requireActivity(), result.uri, getString(R.string.pdf_title));
        });
    }
}
