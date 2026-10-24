package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.PaymentMethodRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Filter panel. Edits a copy of the current filter and hands the result back through the Fragment
 * Result API, so cancelling (dismissing) leaves the list untouched. The search text is preserved.
 */
public class FilterBottomSheet extends ExpandedBottomSheet {

    public static final String RESULT_KEY = "transaction_filter_result";
    public static final String RESULT_FILTER = "filter";

    private static final String TAG = "filter_sheet";
    private static final String ARG_FILTER = "filter";
    private static final String STATE_TYPE = "type";
    private static final String STATE_CATEGORY = "category";
    private static final String STATE_PAYMENT = "payment";
    private static final String STATE_START = "start";
    private static final String STATE_END = "end";
    private static final long NONE = Long.MIN_VALUE;

    public static void show(@NonNull FragmentManager manager, @NonNull TransactionFilter current) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        FilterBottomSheet sheet = new FilterBottomSheet();
        Bundle args = new Bundle();
        args.putSerializable(ARG_FILTER, current);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    private final TimeZone zone = TimeZone.getDefault();
    private String currency;
    private int digits;

    private TransactionFilter base;
    private TransactionType type;
    private long categoryId = NONE;
    private long paymentId = NONE;
    private long startPicked = NONE;
    private long endPicked = NONE;
    private List<CategoryEntity> allCategories = new ArrayList<>();
    private List<CategoryEntity> shownCategories = new ArrayList<>();
    private List<PaymentMethodEntity> payments = new ArrayList<>();

    private MaterialAutoCompleteTextView categoryInput;
    private MaterialAutoCompleteTextView paymentInput;
    private TextInputEditText startInput;
    private TextInputEditText endInput;
    private TextInputLayout endLayout;
    private TextInputEditText minInput;
    private TextInputEditText maxInput;
    private TextInputLayout minLayout;
    private TextInputLayout maxLayout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_filter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        currency = AppPreferences.getCurrencyCode(requireContext());
        digits = CurrencyFormatter.fractionDigits(currency);
        base = (TransactionFilter) requireArguments().getSerializable(ARG_FILTER);
        if (base == null) {
            base = new TransactionFilter();
        }

        categoryInput = view.findViewById(R.id.filter_category);
        paymentInput = view.findViewById(R.id.filter_payment);
        startInput = view.findViewById(R.id.filter_start);
        endInput = view.findViewById(R.id.filter_end);
        endLayout = view.findViewById(R.id.filter_end_layout);
        minInput = view.findViewById(R.id.filter_min);
        maxInput = view.findViewById(R.id.filter_max);
        minLayout = view.findViewById(R.id.filter_min_layout);
        maxLayout = view.findViewById(R.id.filter_max_layout);

        if (savedInstanceState == null) {
            type = base.type;
            categoryId = base.categoryId == null ? NONE : base.categoryId;
            paymentId = base.paymentMethodId == null ? NONE : base.paymentMethodId;
            startPicked = base.startMillis == null ? NONE
                    : DateRanges.pickerUtcFromLocal(base.startMillis, zone);
            endPicked = base.endExclusiveMillis == null ? NONE
                    : DateRanges.pickerUtcFromLocal(base.endExclusiveMillis - 1, zone);
            if (base.minAmountMinor != null) {
                minInput.setText(CurrencyFormatter.plainAmount(base.minAmountMinor, currency));
            }
            if (base.maxAmountMinor != null) {
                maxInput.setText(CurrencyFormatter.plainAmount(base.maxAmountMinor, currency));
            }
        } else {
            String savedType = savedInstanceState.getString(STATE_TYPE);
            type = savedType == null ? null : TransactionType.valueOf(savedType);
            categoryId = savedInstanceState.getLong(STATE_CATEGORY, NONE);
            paymentId = savedInstanceState.getLong(STATE_PAYMENT, NONE);
            startPicked = savedInstanceState.getLong(STATE_START, NONE);
            endPicked = savedInstanceState.getLong(STATE_END, NONE);
        }

        ((android.widget.TextView) view.findViewById(R.id.filter_amount_hint))
                .setText(getString(R.string.filter_amount_hint, currency));
        ((TextInputLayout) view.findViewById(R.id.filter_min_layout))
                .setPrefixText(CurrencyFormatter.symbol(currency));
        ((TextInputLayout) view.findViewById(R.id.filter_max_layout))
                .setPrefixText(CurrencyFormatter.symbol(currency));

        ChipGroup typeGroup = view.findViewById(R.id.filter_type_group);
        typeGroup.check(type == null ? R.id.filter_type_all
                : type == TransactionType.INCOME ? R.id.filter_type_income : R.id.filter_type_expense);
        typeGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) {
                return;
            }
            int id = ids.get(0);
            type = id == R.id.filter_type_income ? TransactionType.INCOME
                    : id == R.id.filter_type_expense ? TransactionType.EXPENSE : null;
            rebuildCategories();
        });

        bindDates();
        View.OnClickListener startPicker = v -> pickDate(true);
        View.OnClickListener endPicker = v -> pickDate(false);
        startInput.setOnClickListener(startPicker);
        ((TextInputLayout) view.findViewById(R.id.filter_start_layout)).setEndIconOnClickListener(startPicker);
        endInput.setOnClickListener(endPicker);
        endLayout.setEndIconOnClickListener(endPicker);
        view.findViewById(R.id.filter_clear_dates).setOnClickListener(v -> {
            startPicked = NONE;
            endPicked = NONE;
            bindDates();
        });

        categoryInput.setOnItemClickListener((parent, v, position, id) ->
                categoryId = position == 0 ? NONE : shownCategories.get(position - 1).id);
        paymentInput.setOnItemClickListener((parent, v, position, id) ->
                paymentId = position == 0 ? NONE : payments.get(position - 1).id);

        view.findViewById(R.id.filter_apply).setOnClickListener(v -> apply());
        view.findViewById(R.id.filter_clear_all).setOnClickListener(v -> deliver(base.withoutSearch(), base.searchText));

        rebuildCategories();
        rebuildPayments();
        CategoryRepository.get(requireContext()).getAll(new RepoCallback<List<CategoryEntity>>() {
            @Override
            public void onSuccess(List<CategoryEntity> result) {
                if (getView() != null) {
                    allCategories = result;
                    rebuildCategories();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                // The category filter simply stays at "All categories".
            }
        });
        PaymentMethodRepository.get(requireContext()).getActive(new RepoCallback<List<PaymentMethodEntity>>() {
            @Override
            public void onSuccess(List<PaymentMethodEntity> result) {
                if (getView() != null) {
                    payments = result;
                    rebuildPayments();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                // The payment filter simply stays at "Any".
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TYPE, type == null ? null : type.name());
        outState.putLong(STATE_CATEGORY, categoryId);
        outState.putLong(STATE_PAYMENT, paymentId);
        outState.putLong(STATE_START, startPicked);
        outState.putLong(STATE_END, endPicked);
    }

    private void rebuildCategories() {
        shownCategories = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.filter_category_all));
        String selectedName = null;
        for (CategoryEntity category : allCategories) {
            if (type != null && category.type != type) {
                continue;
            }
            shownCategories.add(category);
            names.add(type == null
                    ? getString(R.string.category_with_type, category.name, getString(
                            category.type == TransactionType.INCOME ? R.string.type_income : R.string.type_expense))
                    : category.name);
            if (category.id == categoryId) {
                selectedName = names.get(names.size() - 1);
            }
        }
        if (selectedName == null) {
            categoryId = NONE;
        }
        categoryInput.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.item_dropdown, names));
        categoryInput.setText(selectedName == null ? getString(R.string.filter_category_all) : selectedName, false);
    }

    private void rebuildPayments() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.filter_payment_any));
        String selectedName = null;
        for (PaymentMethodEntity method : payments) {
            names.add(method.name);
            if (method.id == paymentId) {
                selectedName = method.name;
            }
        }
        if (selectedName == null && !payments.isEmpty()) {
            paymentId = NONE;
        }
        paymentInput.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.item_dropdown, names));
        paymentInput.setText(selectedName == null ? getString(R.string.filter_payment_any) : selectedName, false);
    }

    private void bindDates() {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        startInput.setText(startPicked == NONE ? "" : format.format(startPicked));
        endInput.setText(endPicked == NONE ? "" : format.format(endPicked));
        endLayout.setError(null);
    }

    private void pickDate(boolean isStart) {
        long current = isStart ? startPicked : endPicked;
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.select_date)
                .setSelection(current != NONE ? current
                        : DateRanges.pickerUtcFromLocal(System.currentTimeMillis(), zone))
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (isStart) {
                startPicked = selection;
            } else {
                endPicked = selection;
            }
            bindDates();
        });
        picker.show(getChildFragmentManager(), isStart ? "pick_start" : "pick_end");
    }

    private void apply() {
        boolean valid = true;

        endLayout.setError(null);
        if (startPicked != NONE && endPicked != NONE && startPicked > endPicked) {
            endLayout.setError(getString(R.string.filter_error_dates));
            valid = false;
        }

        Long min = parseAmount(minInput, minLayout);
        Long max = parseAmount(maxInput, maxLayout);
        boolean amountsOk = !isError(min) && !isError(max);
        if (!amountsOk) {
            valid = false;
        } else if (min != null && max != null && min > max) {
            maxLayout.setError(getString(R.string.filter_error_amounts));
            valid = false;
        }
        if (!valid) {
            return;
        }

        TransactionFilter result = base.copy();
        result.type = type;
        result.categoryId = categoryId == NONE ? null : categoryId;
        result.paymentMethodId = paymentId == NONE ? null : paymentId;
        result.startMillis = startPicked == NONE ? null : DateRanges.localStartOfPickedDay(startPicked, zone);
        // The end day is inclusive: the range ends at the first moment of the following day.
        result.endExclusiveMillis = endPicked == NONE ? null
                : DateRanges.startOfNextDay(DateRanges.localStartOfPickedDay(endPicked, zone), zone);
        result.minAmountMinor = min;
        result.maxAmountMinor = max;
        deliver(result, base.searchText);
    }

    private static boolean isError(@Nullable Long value) {
        return value != null && value == Long.MIN_VALUE;
    }

    /**
     * @return null for blank input, the amount in minor units, or {@link Long#MIN_VALUE} after
     * showing an error on {@code layout}
     */
    @Nullable
    private Long parseAmount(@NonNull TextInputEditText input, @NonNull TextInputLayout layout) {
        layout.setError(null);
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        AmountParser.Result result = AmountParser.parse(text, digits);
        if (result.isValid()) {
            return result.minorUnits;
        }
        layout.setError(errorMessage(result.error));
        return Long.MIN_VALUE;
    }

    @NonNull
    private String errorMessage(@NonNull AmountParser.Error error) {
        switch (error) {
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

    private void deliver(@NonNull TransactionFilter filter, @NonNull String searchText) {
        filter.searchText = searchText;
        Bundle result = new Bundle();
        result.putSerializable(RESULT_FILTER, filter);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        dismissAllowingStateLoss();
    }
}
