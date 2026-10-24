package com.litemobiletools.dailyexpensetracker.ui.recurring;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.PaymentMethodRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.common.SecondaryActivity;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Create or edit a recurring transaction template. While editing it also shows the status, the
 * occurrences already recorded, and Pause/Resume and Delete. It never moves money.
 */
public class RecurringEditorActivity extends SecondaryActivity {

    public static final String EXTRA_RULE_ID = "rule_id";

    private static final String STATE_TYPE = "type";
    private static final String STATE_CATEGORY = "category";
    private static final String STATE_PAYMENT = "payment";
    private static final String STATE_FREQUENCY = "frequency";
    private static final String STATE_START = "start";
    private static final String STATE_END = "end";
    private static final long NONE = -1L;
    private static final long NO_DATE = Long.MIN_VALUE;
    private static final int HISTORY_LIMIT = 10;

    private final TimeZone zone = TimeZone.getDefault();

    private long ruleId = NONE;
    @Nullable private RecurringRuleEntity rule;
    /**
     * Locale fallback until onCreate reads the actual preference. Never call preferences in a field
     * initializer: the Activity's base context is not attached yet and storage would crash.
     */
    private String currency = CurrencyFormatter.currentCode();
    private TransactionType type = TransactionType.EXPENSE;
    private RecurrenceFrequency frequency = RecurrenceFrequency.MONTHLY;
    private long categoryId = NONE;
    private long paymentId = NONE;
    private long startPicked;
    private long endPicked = NO_DATE;
    private List<CategoryEntity> categories = new ArrayList<>();
    private List<PaymentMethodEntity> payments = new ArrayList<>();
    private int categoryToken;
    private boolean saving;

    private MaterialButtonToggleGroup typeGroup;
    private TextInputLayout amountLayout;
    private TextInputEditText amountInput;
    private TextInputLayout categoryLayout;
    private MaterialAutoCompleteTextView categoryInput;
    private MaterialAutoCompleteTextView paymentInput;
    private MaterialAutoCompleteTextView frequencyInput;
    private TextInputEditText noteInput;
    private TextInputEditText startInput;
    private TextInputLayout endLayout;
    private TextInputEditText endInput;
    private MaterialButton saveButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The user's display currency from Phase 9 preferences (context-dependent, so onCreate only).
        currency = AppPreferences.getCurrencyCode(this);
        ruleId = getIntent().getLongExtra(EXTRA_RULE_ID, NONE);
        initScreen(savedInstanceState, R.layout.activity_recurring_editor,
                ruleId == NONE ? R.string.recurring_editor_new : R.string.recurring_editor_edit);

        typeGroup = findViewById(R.id.rec_type_group);
        amountLayout = findViewById(R.id.rec_amount_layout);
        amountInput = findViewById(R.id.rec_amount);
        categoryLayout = findViewById(R.id.rec_category_layout);
        categoryInput = findViewById(R.id.rec_category);
        paymentInput = findViewById(R.id.rec_payment);
        frequencyInput = findViewById(R.id.rec_frequency);
        noteInput = findViewById(R.id.rec_note);
        startInput = findViewById(R.id.rec_start);
        endLayout = findViewById(R.id.rec_end_layout);
        endInput = findViewById(R.id.rec_end);
        saveButton = findViewById(R.id.rec_save);

        startPicked = DateRanges.pickerUtcFromLocal(System.currentTimeMillis(), zone);
        if (savedInstanceState != null) {
            if ("INCOME".equals(savedInstanceState.getString(STATE_TYPE))) {
                type = TransactionType.INCOME;
            }
            frequency = RecurrenceFrequency.valueOf(
                    savedInstanceState.getString(STATE_FREQUENCY, RecurrenceFrequency.MONTHLY.name()));
            categoryId = savedInstanceState.getLong(STATE_CATEGORY, NONE);
            paymentId = savedInstanceState.getLong(STATE_PAYMENT, NONE);
            startPicked = savedInstanceState.getLong(STATE_START, startPicked);
            endPicked = savedInstanceState.getLong(STATE_END, NO_DATE);
        }
        amountLayout.setPrefixText(CurrencyFormatter.symbol(currency));

        typeGroup.check(type == TransactionType.INCOME ? R.id.rec_type_income : R.id.rec_type_expense);
        typeGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            TransactionType selected = checkedId == R.id.rec_type_income
                    ? TransactionType.INCOME : TransactionType.EXPENSE;
            if (selected != type) {
                type = selected;
                categoryId = NONE;
                loadCategories();
            }
        });
        categoryInput.setOnItemClickListener((p, v, position, id) -> {
            categoryId = categories.get(position).id;
            categoryLayout.setError(null);
        });
        paymentInput.setOnItemClickListener((p, v, position, id) ->
                paymentId = position == 0 ? NONE : payments.get(position - 1).id);

        RecurrenceFrequency[] values = RecurrenceFrequency.values();
        List<String> frequencyNames = new ArrayList<>();
        for (RecurrenceFrequency f : values) {
            frequencyNames.add(getString(RecurringLabels.frequencyRes(f)));
        }
        frequencyInput.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, frequencyNames));
        frequencyInput.setOnItemClickListener((p, v, position, id) -> frequency = values[position]);

        View.OnClickListener pickStart = v -> pickDate(true);
        View.OnClickListener pickEnd = v -> pickDate(false);
        startInput.setOnClickListener(pickStart);
        ((TextInputLayout) findViewById(R.id.rec_start_layout)).setEndIconOnClickListener(pickStart);
        endInput.setOnClickListener(pickEnd);
        endLayout.setEndIconOnClickListener(pickEnd);
        findViewById(R.id.rec_clear_end).setOnClickListener(v -> {
            endPicked = NO_DATE;
            bindDates();
        });
        findViewById(R.id.rec_cancel).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save());

        bindDates();
        bindFrequency();
        if (ruleId == NONE) {
            loadCategories();
            loadPayments();
        } else {
            saveButton.setEnabled(false);
            loadRule(savedInstanceState == null);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TYPE, type.name());
        outState.putString(STATE_FREQUENCY, frequency.name());
        outState.putLong(STATE_CATEGORY, categoryId);
        outState.putLong(STATE_PAYMENT, paymentId);
        outState.putLong(STATE_START, startPicked);
        outState.putLong(STATE_END, endPicked);
    }

    // ---- Loading ---------------------------------------------------------------------

    private void loadRule(boolean populate) {
        RecurringRepository.get(this).get(ruleId, new RepoCallback<RecurringRuleEntity>() {
            @Override
            public void onSuccess(RecurringRuleEntity loaded) {
                if (isDestroyed()) {
                    return;
                }
                if (loaded == null) {
                    Toast.makeText(getApplicationContext(), R.string.recurring_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                rule = loaded;
                currency = loaded.currencyCode;
                amountLayout.setPrefixText(CurrencyFormatter.symbol(currency));
                if (populate) {
                    type = loaded.type;
                    frequency = loaded.frequency;
                    categoryId = loaded.categoryId;
                    paymentId = loaded.paymentMethodId == null ? NONE : loaded.paymentMethodId;
                    startPicked = DateRanges.pickerUtcFromLocal(loaded.startDate, zone);
                    endPicked = loaded.endDate == null ? NO_DATE
                            : DateRanges.pickerUtcFromLocal(loaded.endDate, zone);
                    typeGroup.check(type == TransactionType.INCOME ? R.id.rec_type_income : R.id.rec_type_expense);
                    amountInput.setText(CurrencyFormatter.plainAmount(loaded.amountMinor, currency));
                    noteInput.setText(loaded.note == null ? "" : loaded.note);
                    bindDates();
                    bindFrequency();
                }
                findViewById(R.id.rec_edit_panel).setVisibility(View.VISIBLE);
                bindStatus();
                saveButton.setEnabled(!saving);
                loadCategories();
                loadPayments();
                loadHistory();
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed()) {
                    Snackbar.make(findViewById(R.id.screen_root), R.string.load_failed,
                            Snackbar.LENGTH_INDEFINITE).show();
                }
            }
        });
    }

    private void bindStatus() {
        RecurringRuleEntity r = rule;
        if (r == null) {
            return;
        }
        String status = RecurringLabels.status(this, r);
        if (r.skippedCount > 0) {
            status = status + "\n" + getResources().getQuantityString(
                    R.plurals.recurring_skipped_count, r.skippedCount, r.skippedCount);
        }
        ((TextView) findViewById(R.id.rec_status)).setText(status);
        MaterialButton toggle = findViewById(R.id.rec_toggle_active);
        toggle.setText(r.isActive ? R.string.action_pause : R.string.action_resume);
        toggle.setIconResource(r.isActive ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setOnClickListener(v -> RecurringRepository.get(this).setActive(ruleId, !r.isActive,
                new RepoCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        if (!isDestroyed()) {
                            loadRule(false);
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        Toast.makeText(getApplicationContext(), R.string.change_failed, Toast.LENGTH_LONG).show();
                    }
                }));
        findViewById(R.id.rec_delete).setOnClickListener(v -> confirmDelete());
    }

    private void loadHistory() {
        RecurringRepository.get(this).occurrences(ruleId, HISTORY_LIMIT,
                new RepoCallback<List<RecurringOccurrenceEntity>>() {
                    @Override
                    public void onSuccess(List<RecurringOccurrenceEntity> list) {
                        if (isDestroyed()) {
                            return;
                        }
                        LinearLayout container = findViewById(R.id.rec_history);
                        container.removeAllViews();
                        LayoutInflater inflater = LayoutInflater.from(RecurringEditorActivity.this);
                        if (list.isEmpty()) {
                            View row = inflater.inflate(R.layout.item_detail_row, container, false);
                            ((TextView) row.findViewById(R.id.detail_label)).setText(R.string.recurring_history_empty);
                            row.findViewById(R.id.detail_value).setVisibility(View.GONE);
                            container.addView(row);
                            return;
                        }
                        for (RecurringOccurrenceEntity occurrence : list) {
                            View row = inflater.inflate(R.layout.item_detail_row, container, false);
                            row.findViewById(R.id.detail_label).setVisibility(View.GONE);
                            String text = getString(R.string.recurring_history_line,
                                    RecurringLabels.date(RecurringEditorActivity.this, occurrence.occurrenceDate));
                            ((TextView) row.findViewById(R.id.detail_value)).setText(text);
                            row.setContentDescription(text);
                            container.addView(row);
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        // History is informational; the form still works.
                    }
                });
    }

    private void loadCategories() {
        final int token = ++categoryToken;
        long include = rule != null ? rule.categoryId : NONE;
        CategoryRepository.get(this).getSelectableByType(type, include, new RepoCallback<List<CategoryEntity>>() {
            @Override
            public void onSuccess(List<CategoryEntity> result) {
                if (isDestroyed() || token != categoryToken) {
                    return;
                }
                categories = result;
                List<String> names = new ArrayList<>();
                CategoryEntity selected = null;
                for (CategoryEntity c : result) {
                    names.add(c.name);
                    if (c.id == categoryId) {
                        selected = c;
                    }
                }
                categoryInput.setAdapter(new ArrayAdapter<>(RecurringEditorActivity.this,
                        R.layout.item_dropdown, names));
                if (selected == null) {
                    categoryId = NONE;
                    categoryInput.setText("", false);
                } else {
                    categoryInput.setText(selected.name, false);
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed() && token == categoryToken) {
                    Snackbar.make(findViewById(R.id.screen_root), R.string.add_categories_unavailable,
                            Snackbar.LENGTH_INDEFINITE).show();
                }
            }
        });
    }

    private void loadPayments() {
        PaymentMethodRepository.get(this).getActive(new RepoCallback<List<PaymentMethodEntity>>() {
            @Override
            public void onSuccess(List<PaymentMethodEntity> result) {
                if (isDestroyed()) {
                    return;
                }
                payments = result;
                List<String> names = new ArrayList<>();
                names.add(getString(R.string.payment_method_none));
                PaymentMethodEntity selected = null;
                for (PaymentMethodEntity p : result) {
                    names.add(p.name);
                    if (p.id == paymentId) {
                        selected = p;
                    }
                }
                paymentInput.setAdapter(new ArrayAdapter<>(RecurringEditorActivity.this,
                        R.layout.item_dropdown, names));
                if (selected == null) {
                    paymentId = NONE;
                    paymentInput.setText("", false);
                } else {
                    paymentInput.setText(selected.name, false);
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                // Optional field.
            }
        });
    }

    // ---- Dates -----------------------------------------------------------------------

    private void bindFrequency() {
        frequencyInput.setText(getString(RecurringLabels.frequencyRes(frequency)), false);
    }

    private void bindDates() {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        startInput.setText(format.format(startPicked));
        endInput.setText(endPicked == NO_DATE ? "" : format.format(endPicked));
        endLayout.setError(null);
    }

    private void pickDate(boolean start) {
        long current = start ? startPicked : (endPicked == NO_DATE ? startPicked : endPicked);
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.select_date)
                .setSelection(current)
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (start) {
                startPicked = selection;
            } else {
                endPicked = selection;
            }
            bindDates();
        });
        picker.show(getSupportFragmentManager(), start ? "pick_start" : "pick_end");
    }

    // ---- Saving ----------------------------------------------------------------------

    private void save() {
        if (saving || (ruleId != NONE && rule == null)) {
            return;
        }
        int digits = CurrencyFormatter.fractionDigits(currency);
        boolean valid = true;
        AmountParser.Result amount = AmountParser.parse(
                amountInput.getText() == null ? null : amountInput.getText().toString(), digits);
        if (!amount.isValid()) {
            amountLayout.setError(amountMessage(amount.error, digits));
            valid = false;
        } else {
            amountLayout.setError(null);
        }
        if (categoryId == NONE) {
            categoryLayout.setError(getString(R.string.error_category));
            valid = false;
        } else {
            categoryLayout.setError(null);
        }
        if (endPicked != NO_DATE && endPicked < startPicked) {
            endLayout.setError(getString(R.string.recurring_error_end));
            valid = false;
        }
        if (!valid) {
            return;
        }
        long start = DateRanges.localStartOfPickedDay(startPicked, zone);
        Long end = endPicked == NO_DATE ? null : DateRanges.localStartOfPickedDay(endPicked, zone);
        RecurringRepository.RuleInput input = new RecurringRepository.RuleInput(type, amount.minorUnits, currency,
                categoryId, paymentId == NONE ? null : paymentId,
                noteInput.getText() == null ? null : noteInput.getText().toString(), frequency, start, end);

        saving = true;
        saveButton.setEnabled(false);
        RepoCallback<Object> callback = new RepoCallback<Object>() {
            @Override
            public void onSuccess(Object result) {
                saving = false;
                Toast.makeText(getApplicationContext(), R.string.recurring_saved_toast, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(@NonNull Exception error) {
                saving = false;
                if (isDestroyed()) {
                    return;
                }
                saveButton.setEnabled(true);
                if (error instanceof ValidationException && ((ValidationException) error).getReason()
                        == ValidationException.Reason.END_BEFORE_START) {
                    endLayout.setError(getString(R.string.recurring_error_end));
                } else {
                    Snackbar.make(findViewById(R.id.screen_root), R.string.save_failed, Snackbar.LENGTH_LONG).show();
                }
            }
        };
        RecurringRepository repo = RecurringRepository.get(this);
        if (ruleId == NONE) {
            repo.create(input, new Adapter<Long>(callback));
        } else {
            repo.update(ruleId, input, new Adapter<Boolean>(callback));
        }
    }

    /** Adapts any repository result type to the single save callback. */
    private static final class Adapter<T> implements RepoCallback<T> {
        private final RepoCallback<Object> target;

        Adapter(RepoCallback<Object> target) {
            this.target = target;
        }

        @Override
        public void onSuccess(T result) {
            target.onSuccess(result);
        }

        @Override
        public void onError(@NonNull Exception error) {
            target.onError(error);
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recurring_delete_title)
                .setMessage(R.string.recurring_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        RecurringRepository.get(this).delete(ruleId, new RepoCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean deleted) {
                                Toast.makeText(getApplicationContext(), R.string.recurring_deleted,
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(@NonNull Exception error) {
                                Toast.makeText(getApplicationContext(), R.string.change_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        }))
                .show();
    }

    @NonNull
    private String amountMessage(@NonNull AmountParser.Error error, int digits) {
        @StringRes int res;
        switch (error) {
            case EMPTY:
                res = R.string.error_amount_empty;
                break;
            case NOT_POSITIVE:
                res = R.string.error_amount_not_positive;
                break;
            case TOO_MANY_DECIMALS:
                return digits == 0 ? getString(R.string.error_amount_whole)
                        : getString(R.string.error_amount_decimals, digits);
            case TOO_LARGE:
                res = R.string.error_amount_too_large;
                break;
            default:
                res = R.string.error_amount_invalid;
                break;
        }
        return getString(res);
    }
}
