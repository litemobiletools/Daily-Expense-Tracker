package com.litemobiletools.dailyexpensetracker.ui.add;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionInput;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.PaymentMethodRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The transaction form, used both to add a transaction and (when started with
 * {@link #EXTRA_TRANSACTION_ID}) to edit one. Validation errors keep every entry, a second tap
 * during a save is ignored, and success is reported only after the database confirms it.
 * Leaving the screen without saving discards the changes and leaves the stored record untouched.
 */
public class AddTransactionActivity extends AppCompatActivity {

    /** Optional long extra: id of the transaction to edit. Absent means "add a new transaction". */
    public static final String EXTRA_TRANSACTION_ID = "transaction_id";

    private static final String STATE_TYPE = "type";
    private static final String STATE_CATEGORY_ID = "category_id";
    private static final String STATE_PAYMENT_ID = "payment_id";
    private static final String STATE_DATE = "date_utc_millis";
    private static final String DATE_PICKER_TAG = "date_picker";
    private static final long NONE = -1L;

    private TextInputLayout amountLayout;
    private TextInputEditText amountInput;
    private TextInputLayout categoryLayout;
    private MaterialAutoCompleteTextView categoryInput;
    private MaterialAutoCompleteTextView paymentInput;
    private TextInputLayout dateLayout;
    private TextInputEditText dateInput;
    private TextInputEditText noteInput;
    private MaterialButton saveButton;
    private MaterialButtonToggleGroup typeGroup;

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;

    private long editId = NONE;
    /** The stored record while editing (source of currency, id and original time of day). */
    @Nullable private TransactionEntity original;
    /**
     * Locale fallback until onCreate reads the actual preference. Never call preferences in a field
     * initializer: the Activity's base context is not attached yet and storage would crash.
     */
    private String currencyCode = CurrencyFormatter.currentCode();

    private TransactionType type = TransactionType.EXPENSE;
    private List<CategoryEntity> categories = new ArrayList<>();
    private List<PaymentMethodEntity> paymentMethods = new ArrayList<>();
    private long selectedCategoryId = NONE;
    private long selectedPaymentId = NONE;
    /** Midnight UTC of the chosen day, the format MaterialDatePicker uses. */
    private long selectedDateUtc;
    /** Incremented per category load so a slow, stale result cannot overwrite a newer one. */
    private int categoryLoadToken;
    private boolean saving;
    private boolean recreateAfterSave;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The user's display currency from Phase 9 preferences (context-dependent, so onCreate only).
        currencyCode = AppPreferences.getCurrencyCode(this);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_add_transaction);

        View root = findViewById(R.id.add_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });

        editId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, NONE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(editId == NONE ? R.string.add_transaction_title : R.string.edit_transaction_title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.navigate_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        transactionRepository = TransactionRepository.get(this);
        categoryRepository = CategoryRepository.get(this);

        amountLayout = findViewById(R.id.add_amount_layout);
        amountInput = findViewById(R.id.add_amount);
        categoryLayout = findViewById(R.id.add_category_layout);
        categoryInput = findViewById(R.id.add_category);
        paymentInput = findViewById(R.id.add_payment);
        dateLayout = findViewById(R.id.add_date_layout);
        dateInput = findViewById(R.id.add_date);
        noteInput = findViewById(R.id.add_note);
        saveButton = findViewById(R.id.add_save);
        typeGroup = findViewById(R.id.add_type_group);

        amountLayout.setPrefixText(CurrencyFormatter.symbol(currencyCode));
        selectedDateUtc = DateRanges.pickerUtcFromLocal(System.currentTimeMillis(), TimeZone.getDefault());
        if (savedInstanceState != null) {
            if ("INCOME".equals(savedInstanceState.getString(STATE_TYPE))) {
                type = TransactionType.INCOME;
            }
            selectedDateUtc = savedInstanceState.getLong(STATE_DATE, selectedDateUtc);
            selectedCategoryId = savedInstanceState.getLong(STATE_CATEGORY_ID, NONE);
            selectedPaymentId = savedInstanceState.getLong(STATE_PAYMENT_ID, NONE);
        }

        typeGroup.check(type == TransactionType.INCOME ? R.id.add_type_income : R.id.add_type_expense);
        typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            TransactionType newType = checkedId == R.id.add_type_income
                    ? TransactionType.INCOME : TransactionType.EXPENSE;
            if (newType != type) {
                type = newType;
                selectedCategoryId = NONE;
                loadCategories();
            }
        });

        bindDate();
        clearErrorOnEdit(amountInput, amountLayout);
        categoryInput.setOnItemClickListener((parent, view, position, id) -> {
            selectedCategoryId = categories.get(position).id;
            categoryLayout.setError(null);
        });
        paymentInput.setOnItemClickListener((parent, view, position, id) ->
                selectedPaymentId = position == 0 ? NONE : paymentMethods.get(position - 1).id);

        View.OnClickListener openPicker = v -> showDatePicker();
        dateInput.setOnClickListener(openPicker);
        dateLayout.setEndIconOnClickListener(openPicker);

        findViewById(R.id.add_cancel).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> onSave());

        if (editId == NONE) {
            loadCategories();
            loadPaymentMethods();
        } else {
            saveButton.setEnabled(false);
            loadOriginal(savedInstanceState == null);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TYPE, type.name());
        outState.putLong(STATE_DATE, selectedDateUtc);
        outState.putLong(STATE_CATEGORY_ID, selectedCategoryId);
        outState.putLong(STATE_PAYMENT_ID, selectedPaymentId);
    }

    /**
     * Rotation and size changes do not recreate this screen, so an in-flight save keeps a live
     * Activity to report to. The screen is recreated afterwards to pick up size-dependent resources.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (saving) {
            recreateAfterSave = true;
        } else {
            recreate();
        }
    }

    // ---- Edit mode -------------------------------------------------------------------

    /** Loads the stored transaction; fills the form from it only when {@code populate} is true (not after recreation). */
    private void loadOriginal(boolean populate) {
        transactionRepository.getById(editId, new RepoCallback<TransactionEntity>() {
            @Override
            public void onSuccess(TransactionEntity entity) {
                if (isDestroyed()) {
                    return;
                }
                if (entity == null) {
                    Toast.makeText(getApplicationContext(), R.string.edit_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                original = entity;
                currencyCode = entity.currencyCode;
                amountLayout.setPrefixText(CurrencyFormatter.symbol(currencyCode));
                if (populate) {
                    type = entity.type;
                    selectedCategoryId = entity.categoryId;
                    selectedPaymentId = entity.paymentMethodId == null ? NONE : entity.paymentMethodId;
                    selectedDateUtc = DateRanges.pickerUtcFromLocal(entity.transactionTimestamp,
                            TimeZone.getDefault());
                    typeGroup.check(type == TransactionType.INCOME
                            ? R.id.add_type_income : R.id.add_type_expense);
                    bindDate();
                    amountInput.setText(CurrencyFormatter.plainAmount(entity.amountMinor, currencyCode));
                    noteInput.setText(entity.note == null ? "" : entity.note);
                }
                saveButton.setEnabled(!saving);
                loadCategories();
                loadPaymentMethods();
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed()) {
                    Snackbar.make(findViewById(R.id.add_root), R.string.details_load_failed,
                            Snackbar.LENGTH_INDEFINITE).show();
                }
            }
        });
    }

    // ---- Loading ---------------------------------------------------------------------

    private void loadCategories() {
        final int token = ++categoryLoadToken;
        // While editing, keep the stored category available even if it was deactivated since.
        long include = original != null ? original.categoryId : NONE;
        categoryRepository.getSelectableByType(type, include, new RepoCallback<List<CategoryEntity>>() {
            @Override
            public void onSuccess(List<CategoryEntity> result) {
                if (isDestroyed() || token != categoryLoadToken) {
                    return;
                }
                categories = result;
                List<String> names = new ArrayList<>();
                CategoryEntity selected = null;
                for (CategoryEntity category : result) {
                    names.add(category.name);
                    if (category.id == selectedCategoryId) {
                        selected = category;
                    }
                }
                categoryInput.setAdapter(new ArrayAdapter<>(AddTransactionActivity.this,
                        R.layout.item_dropdown, names));
                if (selected == null) {
                    selectedCategoryId = NONE;
                    categoryInput.setText("", false);
                } else {
                    categoryInput.setText(selected.name, false);
                }
                categoryLayout.setError(null);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed() && token == categoryLoadToken) {
                    Snackbar.make(findViewById(R.id.add_root),
                            R.string.add_categories_unavailable, Snackbar.LENGTH_INDEFINITE).show();
                }
            }
        });
    }

    private void loadPaymentMethods() {
        PaymentMethodRepository.get(this).getActive(new RepoCallback<List<PaymentMethodEntity>>() {
            @Override
            public void onSuccess(List<PaymentMethodEntity> result) {
                if (isDestroyed()) {
                    return;
                }
                paymentMethods = result;
                List<String> names = new ArrayList<>();
                names.add(getString(R.string.payment_method_none));
                PaymentMethodEntity selected = null;
                for (PaymentMethodEntity method : result) {
                    names.add(method.name);
                    if (method.id == selectedPaymentId) {
                        selected = method;
                    }
                }
                paymentInput.setAdapter(new ArrayAdapter<>(AddTransactionActivity.this,
                        R.layout.item_dropdown, names));
                if (selected == null) {
                    selectedPaymentId = NONE;
                    paymentInput.setText("", false);
                } else {
                    paymentInput.setText(selected.name, false);
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                // Payment method is optional; the form still works without the list.
            }
        });
    }

    // ---- Date ------------------------------------------------------------------------

    private void bindDate() {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        dateInput.setText(format.format(selectedDateUtc));
        dateLayout.setError(null);
    }

    private void showDatePicker() {
        if (getSupportFragmentManager().findFragmentByTag(DATE_PICKER_TAG) != null) {
            return;
        }
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.select_date)
                .setSelection(selectedDateUtc)
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            selectedDateUtc = selection;
            bindDate();
        });
        picker.show(getSupportFragmentManager(), DATE_PICKER_TAG);
    }

    // ---- Saving ----------------------------------------------------------------------

    private void onSave() {
        if (saving || (editId != NONE && original == null)) {
            return;
        }
        int digits = CurrencyFormatter.fractionDigits(currencyCode);
        boolean valid = true;

        AmountParser.Result amount = AmountParser.parse(
                amountInput.getText() == null ? null : amountInput.getText().toString(), digits);
        if (!amount.isValid()) {
            showAmountError(amountErrorMessage(amount.error, digits), digits);
            amountInput.requestFocus();
            valid = false;
        } else {
            amountLayout.setError(null);
        }

        if (selectedCategoryId == NONE) {
            categoryLayout.setError(getString(R.string.error_category));
            if (valid) {
                categoryInput.requestFocus();
            }
            valid = false;
        } else {
            categoryLayout.setError(null);
        }

        if (!valid) {
            return;
        }

        String note = noteInput.getText() == null ? null : noteInput.getText().toString();
        TransactionInput input = new TransactionInput(type, amount.minorUnits, currencyCode,
                selectedCategoryId, selectedPaymentId == NONE ? null : selectedPaymentId,
                timestampForSave(), note);

        hideKeyboard();
        setSaving(true);
        if (editId == NONE) {
            transactionRepository.insert(input, saveCallback(R.string.add_saved, digits));
        } else {
            transactionRepository.update(editId, input, saveCallback(R.string.edit_saved, digits));
        }
    }

    /**
     * New transaction: the picked day at the current time of day. Edit: the original instant if the
     * day is unchanged, otherwise the new day at the original time of day.
     */
    private long timestampForSave() {
        TimeZone zone = TimeZone.getDefault();
        if (original == null) {
            return DateRanges.combineDateWithTimeOfDay(selectedDateUtc, System.currentTimeMillis(), zone);
        }
        if (selectedDateUtc == DateRanges.pickerUtcFromLocal(original.transactionTimestamp, zone)) {
            return original.transactionTimestamp;
        }
        return DateRanges.combineDateWithTimeOfDay(selectedDateUtc, original.transactionTimestamp, zone);
    }

    private <T> RepoCallback<T> saveCallback(@StringRes int successMessage, int digits) {
        return new RepoCallback<T>() {
            @Override
            public void onSuccess(T result) {
                // The database has confirmed the write.
                saving = false;
                if (!isDestroyed()) {
                    Toast.makeText(getApplicationContext(), successMessage, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (isDestroyed()) {
                    saving = false;
                    return;
                }
                setSaving(false);
                showSaveError(error, digits);
                if (recreateAfterSave) {
                    recreateAfterSave = false;
                    recreate();
                }
            }
        };
    }

    private void setSaving(boolean value) {
        saving = value;
        saveButton.setEnabled(!value);
    }

    private void showSaveError(@NonNull Exception error, int digits) {
        if (error instanceof ValidationException) {
            switch (((ValidationException) error).getReason()) {
                case AMOUNT_NOT_POSITIVE:
                    showAmountError(R.string.error_amount_not_positive, digits);
                    return;
                case AMOUNT_TOO_LARGE:
                    showAmountError(R.string.error_amount_too_large, digits);
                    return;
                case CATEGORY_NOT_FOUND:
                case CATEGORY_TYPE_MISMATCH:
                case CATEGORY_INACTIVE:
                    selectedCategoryId = NONE;
                    categoryLayout.setError(getString(R.string.error_category));
                    loadCategories();
                    return;
                case TRANSACTION_NOT_FOUND:
                    Toast.makeText(getApplicationContext(), R.string.edit_not_found, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                default:
                    break;
            }
        }
        Snackbar.make(findViewById(R.id.add_root), R.string.add_save_failed, Snackbar.LENGTH_LONG).show();
    }

    @StringRes
    private static int amountErrorMessage(@NonNull AmountParser.Error error, int digits) {
        switch (error) {
            case EMPTY:
                return R.string.error_amount_empty;
            case NOT_POSITIVE:
                return R.string.error_amount_not_positive;
            case TOO_MANY_DECIMALS:
                return digits == 0 ? R.string.error_amount_whole : R.string.error_amount_decimals;
            case TOO_LARGE:
                return R.string.error_amount_too_large;
            default:
                return R.string.error_amount_invalid;
        }
    }

    private void showAmountError(@StringRes int message, int digits) {
        amountLayout.setError(message == R.string.error_amount_decimals
                ? getString(message, digits) : getString(message));
    }

    private void clearErrorOnEdit(@NonNull TextInputEditText input, @NonNull TextInputLayout layout) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        }
    }
}
