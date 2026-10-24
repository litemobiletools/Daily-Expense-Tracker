package com.litemobiletools.dailyexpensetracker.ui.budgets;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.room.InvalidationTracker;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.ui.goals.SavingsGoalsActivity;
import com.litemobiletools.dailyexpensetracker.ui.insights.InsightsActivity;
import com.litemobiletools.dailyexpensetracker.ui.recurring.RecurringActivity;
import com.litemobiletools.dailyexpensetracker.util.PeriodMath;

import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Budgets tab: overall and per-category limits for a month or week, with navigation to earlier periods. */
public class BudgetsFragment extends Fragment {

    private static final String STATE_PERIOD = "period";
    private static final String STATE_ANCHOR = "anchor";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InvalidationTracker.Observer observer;

    private BudgetPeriod period = BudgetPeriod.MONTHLY;
    private long anchor;
    private int generation;

    private TextView periodLabel;
    private View prevButton;
    private View nextButton;
    private View loading;
    private View content;
    private View errorView;
    private FrameLayout overallContainer;
    private LinearLayout categoryContainer;
    private View categoryEmpty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_budgets, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        anchor = System.currentTimeMillis();
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_PERIOD);
            if (saved != null) {
                period = BudgetPeriod.valueOf(saved);
            }
            anchor = savedInstanceState.getLong(STATE_ANCHOR, anchor);
        }

        periodLabel = view.findViewById(R.id.budgets_period_label);
        prevButton = view.findViewById(R.id.budgets_prev);
        nextButton = view.findViewById(R.id.budgets_next);
        loading = view.findViewById(R.id.budgets_loading);
        content = view.findViewById(R.id.budgets_content);
        errorView = view.findViewById(R.id.budgets_error);
        overallContainer = view.findViewById(R.id.budgets_overall_container);
        categoryContainer = view.findViewById(R.id.budgets_category_container);
        categoryEmpty = view.findViewById(R.id.budgets_category_empty);

        MaterialButtonToggleGroup group = view.findViewById(R.id.budgets_period_group);
        group.check(period == BudgetPeriod.WEEKLY ? R.id.budgets_period_weekly : R.id.budgets_period_monthly);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            BudgetPeriod selected = checkedId == R.id.budgets_period_weekly
                    ? BudgetPeriod.WEEKLY : BudgetPeriod.MONTHLY;
            if (selected != period) {
                period = selected;
                anchor = System.currentTimeMillis();
                load();
            }
        });
        prevButton.setOnClickListener(v -> {
            anchor = PeriodMath.shift(period, anchor, -1, TimeZone.getDefault());
            load();
        });
        nextButton.setOnClickListener(v -> {
            anchor = PeriodMath.shift(period, anchor, 1, TimeZone.getDefault());
            load();
        });

        view.findViewById(R.id.budgets_goals).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SavingsGoalsActivity.class)));
        view.findViewById(R.id.budgets_recurring).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), RecurringActivity.class)));
        view.findViewById(R.id.budgets_insights).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), InsightsActivity.class)));
        view.findViewById(R.id.budgets_add_fab).setOnClickListener(v -> openEditor(-1));
    }

    @Override
    public void onStart() {
        super.onStart();
        load();
        observer = new InvalidationTracker.Observer("budgets", "transactions", "categories") {
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
        outState.putString(STATE_PERIOD, period.name());
        outState.putLong(STATE_ANCHOR, anchor);
    }

    @Override
    public void onDestroyView() {
        generation++;
        periodLabel = null;
        prevButton = null;
        nextButton = null;
        loading = null;
        content = null;
        errorView = null;
        overallContainer = null;
        categoryContainer = null;
        categoryEmpty = null;
        super.onDestroyView();
    }

    private void openEditor(long budgetId) {
        BudgetEditorSheet.show(getChildFragmentManager(), budgetId, period);
    }

    private void load() {
        if (getView() == null) {
            return;
        }
        final int gen = ++generation;
        updatePeriodHeader();
        BudgetRepository.get(requireContext()).loadProgress(period, AppPreferences.getCurrencyCode(requireContext()), anchor,
                new RepoCallback<List<BudgetProgress>>() {
                    @Override
                    public void onSuccess(List<BudgetProgress> result) {
                        if (gen != generation || getView() == null) {
                            return;
                        }
                        loading.setVisibility(View.GONE);
                        errorView.setVisibility(View.GONE);
                        content.setVisibility(View.VISIBLE);
                        render(result);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        if (gen != generation || getView() == null) {
                            return;
                        }
                        loading.setVisibility(View.GONE);
                        content.setVisibility(View.GONE);
                        errorView.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void updatePeriodHeader() {
        TimeZone zone = TimeZone.getDefault();
        int firstDay = BudgetRepository.firstDayOfWeek();
        long[] range = PeriodMath.range(period, anchor, zone, firstDay);
        if (period == BudgetPeriod.WEEKLY) {
            periodLabel.setText(DateUtils.formatDateRange(requireContext(), range[0], range[1] - 1,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH));
        } else {
            periodLabel.setText(DateUtils.formatDateTime(requireContext(), range[0],
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_MONTH_DAY | DateUtils.FORMAT_SHOW_YEAR));
        }
        // Budgets can be reviewed for past periods but not for periods that have not started.
        long[] current = PeriodMath.range(period, System.currentTimeMillis(), zone, firstDay);
        boolean atCurrent = range[0] >= current[0];
        nextButton.setEnabled(!atCurrent);
        nextButton.setAlpha(atCurrent ? 0.38f : 1f);
    }

    private void render(@NonNull List<BudgetProgress> budgets) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        overallContainer.removeAllViews();
        categoryContainer.removeAllViews();

        BudgetProgress overall = null;
        int categoryCount = 0;
        for (BudgetProgress progress : budgets) {
            if (progress.isOverall()) {
                overall = progress;
            }
        }

        if (overall != null) {
            View card = inflater.inflate(R.layout.item_progress_card, overallContainer, false);
            BudgetRowBinder.bind(card, overall);
            final long id = overall.budget.id;
            card.findViewById(R.id.progress_root).setOnClickListener(v -> openEditor(id));
            overallContainer.addView(card);
        } else {
            View empty = inflater.inflate(R.layout.layout_budget_empty, overallContainer, false);
            ((MaterialButton) empty.findViewById(R.id.budget_empty_action)).setOnClickListener(v -> openEditor(-1));
            overallContainer.addView(empty);
        }

        for (BudgetProgress progress : budgets) {
            if (progress.isOverall()) {
                continue;
            }
            categoryCount++;
            View card = inflater.inflate(R.layout.item_progress_card, categoryContainer, false);
            BudgetRowBinder.bind(card, progress);
            final long id = progress.budget.id;
            card.findViewById(R.id.progress_root).setOnClickListener(v -> openEditor(id));
            categoryContainer.addView(card);
        }
        categoryEmpty.setVisibility(categoryCount == 0 ? View.VISIBLE : View.GONE);
    }
}
