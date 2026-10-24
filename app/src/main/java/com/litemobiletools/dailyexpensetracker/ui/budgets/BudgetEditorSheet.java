package com.litemobiletools.dailyexpensetracker.ui.budgets;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.transactions.ExpandedBottomSheet;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.ArrayList;
import java.util.List;

/** Create, edit or delete one budget. An existing budget's period and category are fixed; only its limit changes. */
public class BudgetEditorSheet extends ExpandedBottomSheet {

    private static final String TAG = "budget_editor";
    private static final String ARG_ID = "budget_id";
    private static final String ARG_PERIOD = "period";
    private static final String STATE_CATEGORY = "category";
    private static final long NONE = -1L;

    public static void show(@NonNull FragmentManager manager, long budgetId, @NonNull BudgetPeriod period) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        BudgetEditorSheet sheet = new BudgetEditorSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, budgetId);
        args.putString(ARG_PERIOD, period.name());
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    private long budgetId = NONE;
    private BudgetPeriod period = BudgetPeriod.MONTHLY;
    @Nullable private BudgetEntity existing;
    private String currency;
    private int digits;
    private long categoryId = NONE; // NONE = overall
    private List<CategoryEntity> categories = new ArrayList<>();
    private boolean saving;

    private MaterialAutoCompleteTextView categoryInput;
    private TextInputLayout categoryLayout;
    private TextInputLayout amountLayout;
    private TextInputEditText amountInput;
    private MaterialButton saveButton;
    private MaterialButtonToggleGroup periodGroup;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_budget_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        budgetId = requireArguments().getLong(ARG_ID, NONE);
        period = BudgetPeriod.valueOf(requireArguments().getString(ARG_PERIOD, BudgetPeriod.MONTHLY.name()));
        currency = AppPreferences.getCurrencyCode(requireContext());
        digits = CurrencyFormatter.fractionDigits(currency);
        if (savedInstanceState != null) {
            categoryId = savedInstanceState.getLong(STATE_CATEGORY, NONE);
        }

        categoryInput = view.findViewById(R.id.budget_editor_category);
        categoryLayout = view.findViewById(R.id.budget_editor_category_layout);
        amountLayout = view.findViewById(R.id.budget_editor_amount_layout);
        amountInput = view.findViewById(R.id.budget_editor_amount);
        saveButton = view.findViewById(R.id.budget_editor_save);
        periodGroup = view.findViewById(R.id.budget_editor_period);

        ((TextView) view.findViewById(R.id.budget_editor_title)).setText(
                budgetId == NONE ? R.string.budget_editor_new : R.string.budget_editor_edit);
        periodGroup.check(period == BudgetPeriod.WEEKLY ? R.id.budget_editor_weekly : R.id.budget_editor_monthly);
        periodGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (isChecked) {
                period = checkedId == R.id.budget_editor_weekly ? BudgetPeriod.WEEKLY : BudgetPeriod.MONTHLY;
            }
        });
        categoryInput.setOnItemClickListener((parent, v, position, id) -> {
            categoryId = position == 0 ? NONE : categories.get(position - 1).id;
            categoryLayout.setError(null);
        });
        saveButton.setOnClickListener(v -> save());
        view.findViewById(R.id.budget_editor_delete).setOnClickListener(v -> confirmDelete());
        ((TextView) view.findViewById(R.id.budget_editor_hint))
                .setText(getString(R.string.currency_hint_budget, currency));

        bindCategories();
        CategoryRepository.get(requireContext()).getActiveByType(TransactionType.EXPENSE,
                new RepoCallback<List<CategoryEntity>>() {
                    @Override
                    public void onSuccess(List<CategoryEntity> result) {
                        if (getView() != null) {
                            categories = result;
                            bindCategories();
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        // The overall budget can still be saved.
                    }
                });
        if (budgetId != NONE) {
            amountLayout.setEnabled(false);
            saveButton.setEnabled(false);
            BudgetRepository.get(requireContext()).get(budgetId, new RepoCallback<BudgetEntity>() {
                @Override
                public void onSuccess(BudgetEntity budget) {
                    if (getView() == null) {
                        return;
                    }
                    if (budget == null) {
                        dismissAllowingStateLoss();
                        return;
                    }
                    existing = budget;
                    period = budget.period;
                    currency = budget.currencyCode;
                    digits = CurrencyFormatter.fractionDigits(currency);
                    categoryId = budget.categoryId == null ? NONE : budget.categoryId;
                    periodGroup.check(period == BudgetPeriod.WEEKLY
                            ? R.id.budget_editor_weekly : R.id.budget_editor_monthly);
                    periodGroup.setEnabled(false);
                    for (int i = 0; i < periodGroup.getChildCount(); i++) {
                        periodGroup.getChildAt(i).setEnabled(false);
                    }
                    categoryLayout.setEnabled(false);
                    amountLayout.setEnabled(true);
                    amountLayout.setPrefixText(CurrencyFormatter.symbol(currency));
                    if (savedInstanceState == null) {
                        amountInput.setText(CurrencyFormatter.plainAmount(budget.amountMinor, currency));
                    }
                    view.findViewById(R.id.budget_editor_delete).setVisibility(View.VISIBLE);
                    saveButton.setEnabled(true);
                    bindCategories();
                }

                @Override
                public void onError(@NonNull Exception error) {
                    if (getView() != null) {
                        Toast.makeText(requireContext(), R.string.load_failed, Toast.LENGTH_LONG).show();
                        dismissAllowingStateLoss();
                    }
                }
            });
        } else {
            amountLayout.setPrefixText(CurrencyFormatter.symbol(currency));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_CATEGORY, categoryId);
    }

    private void bindCategories() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.budget_category_overall));
        String selected = names.get(0);
        for (CategoryEntity category : categories) {
            names.add(category.name);
            if (category.id == categoryId) {
                selected = category.name;
            }
        }
        categoryInput.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.item_dropdown, names));
        categoryInput.setText(selected, false);
    }

    private void save() {
        if (saving) {
            return;
        }
        AmountParser.Result amount = AmountParser.parse(
                amountInput.getText() == null ? null : amountInput.getText().toString(), digits);
        if (!amount.isValid()) {
            amountLayout.setError(amountError(amount.error));
            return;
        }
        amountLayout.setError(null);
        saving = true;
        saveButton.setEnabled(false);
        BudgetRepository.get(requireContext()).save(budgetId == NONE ? null : budgetId, period,
                categoryId == NONE ? null : categoryId, currency, amount.minorUnits, new RepoCallback<Long>() {
                    @Override
                    public void onSuccess(Long id) {
                        saving = false;
                        Toast.makeText(requireContext().getApplicationContext(), R.string.budget_saved,
                                Toast.LENGTH_SHORT).show();
                        dismissAllowingStateLoss();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        saving = false;
                        if (getView() == null) {
                            return;
                        }
                        saveButton.setEnabled(true);
                        if (error instanceof ValidationException
                                && ((ValidationException) error).getReason()
                                == ValidationException.Reason.BUDGET_DUPLICATE) {
                            categoryLayout.setError(getString(R.string.budget_error_duplicate));
                        } else {
                            Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @NonNull
    private String amountError(@NonNull AmountParser.Error error) {
        switch (error) {
            case EMPTY:
                return getString(R.string.error_amount_empty);
            case NOT_POSITIVE:
                return getString(R.string.error_amount_not_positive);
            case TOO_MANY_DECIMALS:
                return digits == 0 ? getString(R.string.error_amount_whole)
                        : getString(R.string.error_amount_decimals, digits);
            case TOO_LARGE:
                return getString(R.string.error_amount_too_large);
            default:
                return getString(R.string.error_amount_invalid);
        }
    }

    private void confirmDelete() {
        if (existing == null) {
            return;
        }
        final long id = existing.id;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.budget_delete_title)
                .setMessage(R.string.budget_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        BudgetRepository.get(requireContext()).delete(id, new RepoCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean deleted) {
                                Toast.makeText(requireContext().getApplicationContext(),
                                        R.string.budget_deleted, Toast.LENGTH_SHORT).show();
                                dismissAllowingStateLoss();
                            }

                            @Override
                            public void onError(@NonNull Exception error) {
                                Toast.makeText(requireContext().getApplicationContext(),
                                        R.string.change_failed, Toast.LENGTH_LONG).show();
                            }
                        }))
                .show();
    }
}
