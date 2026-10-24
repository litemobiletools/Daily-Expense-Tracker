package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.add.AddTransactionActivity;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.HashSet;
import java.util.Set;

/** Edit and delete entry points shared by every screen that shows a transaction. */
public final class TransactionActions {

    /** Ids with a delete in progress, so a repeated confirm cannot send a second delete. */
    private static final Set<Long> DELETES_IN_FLIGHT = new HashSet<>();

    /** How long the Undo action stays available after a delete. */
    private static final int UNDO_WINDOW_MS = 6000;

    private TransactionActions() {
    }

    public static void edit(@NonNull Context context, long transactionId) {
        Intent intent = new Intent(context, AddTransactionActivity.class);
        intent.putExtra(AddTransactionActivity.EXTRA_TRANSACTION_ID, transactionId);
        context.startActivity(intent);
    }

    /**
     * Asks for confirmation, then deletes exactly this transaction. {@code onDeleted} runs after the
     * database confirms the delete; a Snackbar then offers Undo, which restores the same record (same id).
     */
    public static void confirmDelete(@NonNull Activity activity, @NonNull TransactionRow row,
                                     @Nullable Runnable onDeleted) {
        String typeLabel = activity.getString(row.type == TransactionType.INCOME
                ? R.string.type_income : R.string.type_expense);
        String amount = CurrencyFormatter.format(row.amountMinor, row.currencyCode);
        String date = DateUtils.formatDateTime(activity, row.transactionTimestamp,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR);
        String message = row.note == null || row.note.isEmpty()
                ? activity.getString(R.string.delete_message, typeLabel, row.categoryName, amount, date)
                : activity.getString(R.string.delete_message_note, typeLabel, row.categoryName,
                        amount, date, row.note);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.delete_title)
                .setMessage(message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.delete_confirm, (dialog, which) -> delete(activity, row.id, onDeleted))
                .show();
    }

    private static void delete(@NonNull Activity activity, long id, @Nullable Runnable onDeleted) {
        if (!DELETES_IN_FLIGHT.add(id)) {
            return;
        }
        final Context app = activity.getApplicationContext();
        TransactionRepository.get(app).deleteReturning(id, new RepoCallback<TransactionEntity>() {
            @Override
            public void onSuccess(TransactionEntity deleted) {
                DELETES_IN_FLIGHT.remove(id);
                if (onDeleted != null) {
                    onDeleted.run();
                }
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (deleted == null) {
                    return; // Already gone; nothing to undo.
                }
                showUndo(activity, deleted);
            }

            @Override
            public void onError(@NonNull Exception error) {
                DELETES_IN_FLIGHT.remove(id);
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    Toast.makeText(app, R.string.delete_failed, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static void showUndo(@NonNull Activity activity, @NonNull TransactionEntity deleted) {
        View anchor = activity.findViewById(R.id.bottom_nav);
        View root = activity.findViewById(android.R.id.content);
        Snackbar snackbar = Snackbar.make(root, R.string.delete_done, UNDO_WINDOW_MS)
                .setAction(R.string.delete_undo, v -> restore(activity, deleted));
        if (anchor != null) {
            snackbar.setAnchorView(anchor);
        }
        snackbar.show();
    }

    private static void restore(@NonNull Activity activity, @NonNull TransactionEntity deleted) {
        final Context app = activity.getApplicationContext();
        TransactionRepository.get(app).restore(deleted, new RepoCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean restored) {
                Toast.makeText(app, restored ? R.string.restore_done : R.string.restore_failed,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull Exception error) {
                Toast.makeText(app, R.string.restore_failed, Toast.LENGTH_LONG).show();
            }
        });
    }
}
