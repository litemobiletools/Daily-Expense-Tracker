package com.litemobiletools.dailyexpensetracker.ui.goals;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.repository.GoalRepository;
import com.litemobiletools.dailyexpensetracker.ui.transactions.ExpandedBottomSheet;
import com.litemobiletools.dailyexpensetracker.util.AmountParser;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.List;

/** One goal: progress, add or take out money (history is kept), complete or reopen, edit, delete. */
public class GoalDetailsSheet extends ExpandedBottomSheet {

    private static final String TAG = "goal_details";
    private static final String ARG_ID = "goal_id";
    private static final int HISTORY_LIMIT = 20;

    public static void show(@NonNull FragmentManager manager, long goalId) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        GoalDetailsSheet sheet = new GoalDetailsSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, goalId);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    private long goalId;
    @Nullable private GoalWithSaved current;
    private int generation;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_goal_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        goalId = requireArguments().getLong(ARG_ID);
        reload();
    }

    @Override
    public void onDestroyView() {
        generation++;
        super.onDestroyView();
    }

    private void reload() {
        final int gen = ++generation;
        final GoalRepository repo = GoalRepository.get(requireContext());
        repo.get(goalId, new RepoCallback<GoalWithSaved>() {
            @Override
            public void onSuccess(GoalWithSaved item) {
                if (gen != generation || getView() == null) {
                    return;
                }
                if (item == null) {
                    showStatus(R.string.details_not_found);
                    return;
                }
                current = item;
                bind(item);
                repo.contributions(goalId, HISTORY_LIMIT, new RepoCallback<List<GoalContributionEntity>>() {
                    @Override
                    public void onSuccess(List<GoalContributionEntity> history) {
                        if (gen == generation && getView() != null) {
                            bindHistory(item, history);
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        // The progress is shown; the history list simply stays empty.
                    }
                });
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (gen == generation && getView() != null) {
                    showStatus(R.string.details_load_failed);
                }
            }
        });
    }

    private void showStatus(int message) {
        View view = requireView();
        view.findViewById(R.id.goal_details_progress).setVisibility(View.GONE);
        TextView status = view.findViewById(R.id.goal_details_status);
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void bind(@NonNull GoalWithSaved item) {
        View view = requireView();
        View progress = view.findViewById(R.id.goal_details_progress);
        progress.setClickable(false);
        GoalRowBinder.bind(progress, item);
        view.findViewById(R.id.goal_details_actions).setVisibility(View.VISIBLE);

        view.findViewById(R.id.goal_details_add).setOnClickListener(v -> showContributionDialog(item, true));
        MaterialButton withdraw = view.findViewById(R.id.goal_details_withdraw);
        withdraw.setEnabled(item.savedMinor > 0);
        withdraw.setOnClickListener(v -> showContributionDialog(item, false));

        MaterialButton complete = view.findViewById(R.id.goal_details_complete);
        complete.setText(item.goal.isCompleted ? R.string.goal_reopen : R.string.goal_mark_completed);
        complete.setOnClickListener(v -> GoalRepository.get(requireContext()).setCompleted(goalId,
                !item.goal.isCompleted, refreshAfterChange()));
        view.findViewById(R.id.goal_details_edit).setOnClickListener(v -> {
            FragmentManager manager = getParentFragmentManager();
            dismissAllowingStateLoss();
            GoalEditorSheet.show(manager, goalId);
        });
        view.findViewById(R.id.goal_details_delete).setOnClickListener(v -> confirmDelete(item));
    }

    private void bindHistory(@NonNull GoalWithSaved item, @NonNull List<GoalContributionEntity> history) {
        LinearLayout container = requireView().findViewById(R.id.goal_details_history);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        if (history.isEmpty()) {
            View row = inflater.inflate(R.layout.item_detail_row, container, false);
            ((TextView) row.findViewById(R.id.detail_label)).setText(R.string.goal_history_empty);
            row.findViewById(R.id.detail_value).setVisibility(View.GONE);
            container.addView(row);
            return;
        }
        for (GoalContributionEntity entry : history) {
            String amount = CurrencyFormatter.format(Math.abs(entry.deltaMinor), item.goal.currencyCode);
            String signed = (entry.deltaMinor > 0 ? "+" : "−") + amount;
            String when = DateUtils.formatDateTime(requireContext(), entry.contributedAt,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
            View row = inflater.inflate(R.layout.item_detail_row, container, false);
            ((TextView) row.findViewById(R.id.detail_label)).setText(when);
            String value = entry.note == null ? signed : getString(R.string.goal_contribution_line, signed, entry.note);
            ((TextView) row.findViewById(R.id.detail_value)).setText(value);
            row.setContentDescription(when + ", " + value);
            container.addView(row);
        }
    }

    private RepoCallback<Boolean> refreshAfterChange() {
        return new RepoCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (getView() != null) {
                    reload();
                }
            }

            @Override
            public void onError(@NonNull Exception error) {
                Toast.makeText(requireContext().getApplicationContext(), R.string.change_failed,
                        Toast.LENGTH_LONG).show();
            }
        };
    }

    private void showContributionDialog(@NonNull GoalWithSaved item, boolean add) {
        final String currency = item.goal.currencyCode;
        final int digits = CurrencyFormatter.fractionDigits(currency);
        View form = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_contribution, null);
        TextInputLayout amountLayout = form.findViewById(R.id.contribution_amount_layout);
        amountLayout.setPrefixText(CurrencyFormatter.symbol(currency));
        TextInputEditText amountInput = form.findViewById(R.id.contribution_amount);
        TextInputEditText noteInput = form.findViewById(R.id.contribution_note);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(add ? R.string.goal_contribution_add : R.string.goal_contribution_withdraw,
                        item.goal.name))
                .setView(form)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.goal_contribution_confirm, null)
                .create();
        dialog.show();
        // Validate before closing: override the default click that always dismisses.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            AmountParser.Result amount = AmountParser.parse(
                    amountInput.getText() == null ? null : amountInput.getText().toString(), digits);
            if (!amount.isValid()) {
                amountLayout.setError(amountMessage(amount.error, digits));
                return;
            }
            if (!add && amount.minorUnits > item.savedMinor) {
                amountLayout.setError(getString(R.string.goal_error_withdraw));
                return;
            }
            v.setEnabled(false);
            String note = noteInput.getText() == null ? null : noteInput.getText().toString();
            GoalRepository.get(requireContext().getApplicationContext()).contribute(goalId,
                    add ? amount.minorUnits : -amount.minorUnits, note, new RepoCallback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            dialog.dismiss();
                            Toast.makeText(requireContext().getApplicationContext(),
                                    R.string.goal_updated_toast, Toast.LENGTH_SHORT).show();
                            if (getView() != null) {
                                reload();
                            }
                        }

                        @Override
                        public void onError(@NonNull Exception error) {
                            v.setEnabled(true);
                            if (error instanceof ValidationException && ((ValidationException) error).getReason()
                                    == ValidationException.Reason.WITHDRAWAL_EXCEEDS_SAVED) {
                                amountLayout.setError(getString(R.string.goal_error_withdraw));
                            } else if (error instanceof ValidationException && ((ValidationException) error)
                                    .getReason() == ValidationException.Reason.AMOUNT_TOO_LARGE) {
                                amountLayout.setError(getString(R.string.error_amount_too_large));
                            } else {
                                amountLayout.setError(getString(R.string.change_failed));
                            }
                        }
                    });
        });
    }

    @NonNull
    private String amountMessage(@NonNull AmountParser.Error error, int digits) {
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

    private void confirmDelete(@NonNull GoalWithSaved item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.goal_delete_title)
                .setMessage(getString(R.string.goal_delete_message, item.goal.name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        GoalRepository.get(requireContext().getApplicationContext()).delete(goalId,
                                new RepoCallback<Boolean>() {
                                    @Override
                                    public void onSuccess(Boolean deleted) {
                                        Toast.makeText(requireContext().getApplicationContext(),
                                                R.string.goal_deleted, Toast.LENGTH_SHORT).show();
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
