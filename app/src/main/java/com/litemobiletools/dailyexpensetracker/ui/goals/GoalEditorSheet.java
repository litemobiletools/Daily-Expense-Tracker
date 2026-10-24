package com.litemobiletools.dailyexpensetracker.ui.goals;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.repository.GoalRepository;
import com.litemobiletools.dailyexpensetracker.ui.transactions.ExpandedBottomSheet;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.DateRanges;

import java.text.DateFormat;
import java.util.TimeZone;

/** Create or edit a savings goal: name, target amount and an optional target date. */
public class GoalEditorSheet extends ExpandedBottomSheet {

    private static final String TAG = "goal_editor";
    private static final String ARG_ID = "goal_id";
    private static final String STATE_DATE = "date";
    private static final long NONE = Long.MIN_VALUE;

    public static void show(@NonNull FragmentManager manager, long goalId) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        GoalEditorSheet sheet = new GoalEditorSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, goalId);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    private long goalId = -1;
    private String currency;
    private int digits;
    /** Midnight UTC of the picked day, or NONE. */
    private long datePicked = NONE;
    private boolean saving;

    private TextInputLayout nameLayout;
    private TextInputEditText nameInput;
    private TextInputLayout targetLayout;
    private TextInputEditText targetInput;
    private TextInputEditText dateInput;
    private MaterialButton saveButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_goal_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        goalId = requireArguments().getLong(ARG_ID, -1);
        currency = AppPreferences.getCurrencyCode(requireContext());
        digits = CurrencyFormatter.fractionDigits(currency);
        if (savedInstanceState != null) {
            datePicked = savedInstanceState.getLong(STATE_DATE, NONE);
        }
        nameLayout = view.findViewById(R.id.goal_editor_name_layout);
        nameInput = view.findViewById(R.id.goal_editor_name);
        targetLayout = view.findViewById(R.id.goal_editor_target_layout);
        targetInput = view.findViewById(R.id.goal_editor_target);
        dateInput = view.findViewById(R.id.goal_editor_date);
        saveButton = view.findViewById(R.id.goal_editor_save);
        ((TextView) view.findViewById(R.id.goal_editor_title)).setText(
                goalId < 0 ? R.string.goal_editor_new : R.string.goal_editor_edit);
        targetLayout.setPrefixText(CurrencyFormatter.symbol(currency));

        View.OnClickListener pick = v -> pickDate();
        dateInput.setOnClickListener(pick);
        ((TextInputLayout) view.findViewById(R.id.goal_editor_date_layout)).setEndIconOnClickListener(pick);
        view.findViewById(R.id.goal_editor_clear_date).setOnClickListener(v -> {
            datePicked = NONE;
            bindDate();
        });
        saveButton.setOnClickListener(v -> save());
        bindDate();

        if (goalId >= 0) {
            saveButton.setEnabled(false);
            GoalRepository.get(requireContext()).get(goalId, new RepoCallback<GoalWithSaved>() {
                @Override
                public void onSuccess(GoalWithSaved item) {
                    if (getView() == null) {
                        return;
                    }
                    if (item == null) {
                        dismissAllowingStateLoss();
                        return;
                    }
                    currency = item.goal.currencyCode;
                    digits = CurrencyFormatter.fractionDigits(currency);
                    targetLayout.setPrefixText(CurrencyFormatter.symbol(currency));
                    if (savedInstanceState == null) {
                        nameInput.setText(item.goal.name);
                        targetInput.setText(CurrencyFormatter.plainAmount(item.goal.targetMinor, currency));
                        datePicked = item.goal.targetDate == null ? NONE
                                : DateRanges.pickerUtcFromLocal(item.goal.targetDate, TimeZone.getDefault());
                        bindDate();
                    }
                    saveButton.setEnabled(true);
                }

                @Override
                public void onError(@NonNull Exception error) {
                    if (getView() != null) {
                        Toast.makeText(requireContext(), R.string.load_failed, Toast.LENGTH_LONG).show();
                        dismissAllowingStateLoss();
                    }
                }
            });
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_DATE, datePicked);
    }

    private void bindDate() {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        dateInput.setText(datePicked == NONE ? "" : format.format(datePicked));
    }

    private void pickDate() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.select_date)
                .setSelection(datePicked != NONE ? datePicked
                        : DateRanges.pickerUtcFromLocal(System.currentTimeMillis(), TimeZone.getDefault()))
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            datePicked = selection;
            bindDate();
        });
        picker.show(getChildFragmentManager(), "goal_date");
    }

    private void save() {
        if (saving) {
            return;
        }
        String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        boolean valid = true;
        if (name.isEmpty()) {
            nameLayout.setError(getString(R.string.goal_error_name));
            valid = false;
        } else {
            nameLayout.setError(null);
        }
        AmountParser.Result target = AmountParser.parse(
                targetInput.getText() == null ? null : targetInput.getText().toString(), digits);
        if (!target.isValid()) {
            targetLayout.setError(amountError(target.error));
            valid = false;
        } else {
            targetLayout.setError(null);
        }
        if (!valid) {
            return;
        }
        Long targetDate = datePicked == NONE ? null
                : DateRanges.localStartOfPickedDay(datePicked, TimeZone.getDefault());
        saving = true;
        saveButton.setEnabled(false);
        GoalRepository repo = GoalRepository.get(requireContext());
        if (goalId < 0) {
            repo.create(name, target.minorUnits, currency, targetDate, new Result<Long>());
        } else {
            repo.update(goalId, name, target.minorUnits, targetDate, new Result<Boolean>());
        }
    }

    private final class Result<T> implements RepoCallback<T> {
        @Override
        public void onSuccess(T result) {
            saving = false;
            Toast.makeText(requireContext().getApplicationContext(), R.string.goal_saved_toast,
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
            if (error instanceof ValidationException && ((ValidationException) error).getReason()
                    == ValidationException.Reason.GOAL_NAME_INVALID) {
                nameLayout.setError(getString(R.string.goal_error_name));
            } else {
                Toast.makeText(requireContext(), R.string.save_failed, Toast.LENGTH_LONG).show();
            }
        }
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
}
