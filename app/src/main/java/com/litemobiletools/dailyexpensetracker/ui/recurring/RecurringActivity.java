package com.litemobiletools.dailyexpensetracker.ui.recurring;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;
import androidx.room.InvalidationTracker;

import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleRow;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.model.MissedPolicy;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.ui.common.SecondaryActivity;
import com.litemobiletools.dailyexpensetracker.ui.transactions.CategoryIcons;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.List;
import java.util.Set;

/** Lists recurring rules, explains what they do, and holds the "missed occurrences" setting. */
public class RecurringActivity extends SecondaryActivity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InvalidationTracker.Observer observer;
    private int generation;

    private LinearLayout container;
    private View listCard;
    private View emptyView;
    private View errorView;
    private TextView policySummary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initScreen(savedInstanceState, R.layout.activity_recurring, R.string.recurring_title);
        container = findViewById(R.id.recurring_container);
        listCard = findViewById(R.id.recurring_list_card);
        emptyView = findViewById(R.id.recurring_empty);
        errorView = findViewById(R.id.recurring_error);
        policySummary = findViewById(R.id.recurring_policy_summary);
        findViewById(R.id.recurring_add_fab).setOnClickListener(v ->
                startActivity(new Intent(this, RecurringEditorActivity.class)));
        findViewById(R.id.recurring_policy_row).setOnClickListener(v -> showPolicyDialog());
        bindPolicySummary();
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
        observer = new InvalidationTracker.Observer("recurring_rules", "recurring_occurrences") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                mainHandler.post(() -> {
                    if (!isDestroyed()) {
                        load();
                    }
                });
            }
        };
        AppDatabase.getInstance(this).getInvalidationTracker().addObserver(observer);
    }

    @Override
    protected void onStop() {
        if (observer != null) {
            AppDatabase.getInstance(this).getInvalidationTracker().removeObserver(observer);
            observer = null;
        }
        super.onStop();
    }

    private void bindPolicySummary() {
        MissedPolicy policy = AppPreferences.getMissedPolicy(this);
        policySummary.setText(policy == MissedPolicy.CATCH_UP
                ? R.string.recurring_missed_summary_catch_up : R.string.recurring_missed_summary_skip);
    }

    private void showPolicyDialog() {
        CharSequence[] labels = {
                getString(R.string.recurring_missed_catch_up, RecurringRepository.MAX_CATCH_UP),
                getString(R.string.recurring_missed_skip)
        };
        MissedPolicy[] values = {MissedPolicy.CATCH_UP, MissedPolicy.SKIP_MISSED};
        int checked = AppPreferences.getMissedPolicy(this) == MissedPolicy.CATCH_UP ? 0 : 1;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recurring_missed_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppPreferences.setMissedPolicy(this, values[which]);
                    bindPolicySummary();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void load() {
        final int gen = ++generation;
        RecurringRepository.get(this).listRows(new RepoCallback<List<RecurringRuleRow>>() {
            @Override
            public void onSuccess(List<RecurringRuleRow> rows) {
                if (isDestroyed() || gen != generation) {
                    return;
                }
                errorView.setVisibility(View.GONE);
                render(rows);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed() && gen == generation) {
                    errorView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void render(@NonNull List<RecurringRuleRow> rows) {
        container.removeAllViews();
        listCard.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < rows.size(); i++) {
            RecurringRuleRow row = rows.get(i);
            View view = inflater.inflate(R.layout.item_transaction, container, false);
            bindRow(view, row);
            final long id = row.rule.id;
            view.setOnClickListener(v -> {
                Intent intent = new Intent(this, RecurringEditorActivity.class);
                intent.putExtra(RecurringEditorActivity.EXTRA_RULE_ID, id);
                startActivity(intent);
            });
            container.addView(view);
            if (i < rows.size() - 1) {
                container.addView(new MaterialDivider(this));
            }
        }
    }

    private void bindRow(@NonNull View view, @NonNull RecurringRuleRow row) {
        RecurringRuleEntity rule = row.rule;
        boolean income = rule.type == TransactionType.INCOME;
        int typeColor = ContextCompat.getColor(this, income ? R.color.income : R.color.expense);
        int iconColor = row.categoryColorArgb != null ? row.categoryColorArgb : typeColor;
        ImageView icon = view.findViewById(R.id.transaction_icon);
        icon.setImageResource(CategoryIcons.resFor(row.categoryIconKey));
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(iconColor));
        icon.setBackgroundResource(R.drawable.bg_circle_solid);
        icon.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(iconColor, 0x2E)));

        String title = rule.note != null && !rule.note.isEmpty() ? rule.note : row.categoryName;
        String status = RecurringLabels.status(this, rule);
        String frequency = getString(RecurringLabels.frequencyRes(rule.frequency));
        String amount = CurrencyFormatter.format(rule.amountMinor, rule.currencyCode);
        ((TextView) view.findViewById(R.id.transaction_title)).setText(title);
        ((TextView) view.findViewById(R.id.transaction_subtitle)).setText(
                getString(R.string.recurring_row_summary, row.categoryName, frequency, status));
        TextView amountView = view.findViewById(R.id.transaction_amount);
        amountView.setText(getString(income ? R.string.signed_income : R.string.signed_expense, amount));
        amountView.setTextColor(typeColor);
        String typeLabel = getString(income ? R.string.type_income : R.string.type_expense);
        view.setContentDescription(typeLabel + ", " + title + ", " + amount + ", " + frequency + ", " + status
                + (rule.skippedCount > 0 ? ", " + getResources().getQuantityString(
                        R.plurals.recurring_skipped_count, rule.skippedCount, rule.skippedCount) : ""));
    }
}
