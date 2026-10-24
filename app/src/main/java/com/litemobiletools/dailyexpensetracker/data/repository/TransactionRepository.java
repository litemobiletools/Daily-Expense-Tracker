package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.HistoryPage;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.util.LikeEscaper;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.Totals;
import com.litemobiletools.dailyexpensetracker.data.TransactionInput;
import com.litemobiletools.dailyexpensetracker.data.TransactionValidator;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.ValidationException.Reason;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.List;

/**
 * Transaction operations. The {@code ...Sync} methods must run on a worker thread (Room
 * refuses main-thread access); the callback variants run them on the database executor and
 * deliver results on the main thread. Multi-step writes run in one database transaction.
 */
public class TransactionRepository {

    private final AppDatabase db;

    public TransactionRepository(@NonNull AppDatabase db) {
        this.db = db;
    }

    public static TransactionRepository get(@NonNull Context context) {
        return new TransactionRepository(AppDatabase.getInstance(context));
    }

    // ---- Async API -------------------------------------------------------------------

    public void insert(@NonNull TransactionInput input, @Nullable RepoCallback<Long> callback) {
        DatabaseExecutor.run(() -> insertSync(input), callback);
    }

    public void update(long id, @NonNull TransactionInput input, @Nullable RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> {
            updateSync(id, input);
            return Boolean.TRUE;
        }, callback);
    }

    public void delete(long id, @Nullable RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> deleteSync(id), callback);
    }

    /** Loads one transaction (joined for display); the result is null if it no longer exists. */
    public void getRow(long id, @NonNull RepoCallback<TransactionRow> callback) {
        DatabaseExecutor.run(() -> getRowSync(id), callback);
    }

    public void getById(long id, @NonNull RepoCallback<TransactionEntity> callback) {
        DatabaseExecutor.run(() -> getByIdSync(id), callback);
    }

    /**
     * Loads a page of history. The total count is computed in the same database transaction
     * when {@code withCount} is true, so count and rows agree.
     */
    public void loadHistory(@NonNull TransactionFilter filter, @NonNull String currency, int limit,
                            int offset, boolean withCount, @NonNull RepoCallback<HistoryPage> callback) {
        final TransactionFilter snapshot = filter.copy();
        DatabaseExecutor.run(() -> loadHistorySync(snapshot, currency, limit, offset, withCount), callback);
    }

    /**
     * Deletes a transaction and returns what was deleted (null if the id did not exist), so the
     * caller can offer Undo through {@link #restore}.
     */
    public void deleteReturning(long id, @NonNull RepoCallback<TransactionEntity> callback) {
        DatabaseExecutor.run(() -> deleteReturningSync(id), callback);
    }

    /** Undo for a delete: puts the transaction back under its original id. */
    public void restore(@NonNull TransactionEntity entity, @NonNull RepoCallback<Boolean> callback) {
        DatabaseExecutor.run(() -> restoreSync(entity), callback);
    }

    // ---- Synchronous API (worker thread only) ----------------------------------------

    @Nullable
    @WorkerThread
    public TransactionRow getRowSync(long id) {
        return db.transactionDao().getRowById(id);
    }

    @NonNull
    @WorkerThread
    public HistoryPage loadHistorySync(@NonNull TransactionFilter f, @NonNull String currency,
                                       int limit, int offset, boolean withCount) {
        final String pattern = LikeEscaper.contains(f.searchText);
        // Type words ("income", "expense") only match from 3 typed characters, by prefix, so a
        // single letter does not select a whole type.
        final String typePattern = LikeEscaper.prefix(f.searchText, 3);
        return db.runInTransaction(() -> {
            int total = withCount ? db.transactionDao().countHistory(f.type, f.categoryId,
                    f.paymentMethodId, f.startMillis, f.endExclusiveMillis, f.minAmountMinor,
                    f.maxAmountMinor, currency, pattern, typePattern) : -1;
            List<TransactionRow> rows = db.transactionDao().queryHistory(f.type, f.categoryId,
                    f.paymentMethodId, f.startMillis, f.endExclusiveMillis, f.minAmountMinor,
                    f.maxAmountMinor, currency, pattern, typePattern, limit, offset);
            return new HistoryPage(total, rows);
        });
    }

    @Nullable
    @WorkerThread
    public TransactionEntity deleteReturningSync(long id) {
        return db.runInTransaction(() -> {
            TransactionEntity existing = db.transactionDao().getById(id);
            if (existing == null) {
                return null;
            }
            db.transactionDao().deleteById(id);
            return existing;
        });
    }

    /** @return true if restored, false if a transaction with that id already exists */
    @WorkerThread
    public boolean restoreSync(@NonNull TransactionEntity entity) throws Exception {
        return db.runInTransaction(() -> {
            if (db.transactionDao().getById(entity.id) != null) {
                return false;
            }
            if (db.categoryDao().getById(entity.categoryId) == null) {
                throw new ValidationException(Reason.CATEGORY_NOT_FOUND);
            }
            if (entity.paymentMethodId != null
                    && db.paymentMethodDao().getById(entity.paymentMethodId) == null) {
                throw new ValidationException(Reason.PAYMENT_METHOD_NOT_FOUND);
            }
            db.transactionDao().insert(entity);
            return true;
        });
    }

    /** @return the new transaction id */
    @WorkerThread
    public long insertSync(@NonNull TransactionInput input) throws Exception {
        TransactionValidator.validate(input);
        return db.runInTransaction(() -> {
            checkReferences(input, null);
            long now = System.currentTimeMillis();
            TransactionEntity entity = new TransactionEntity();
            apply(entity, input);
            entity.createdAt = now;
            entity.updatedAt = now;
            return db.transactionDao().insert(entity);
        });
    }

    /** Updates only the transaction with {@code id}; keeps its creation time. */
    @WorkerThread
    public void updateSync(long id, @NonNull TransactionInput input) throws Exception {
        TransactionValidator.validate(input);
        db.runInTransaction(() -> {
            TransactionEntity existing = db.transactionDao().getById(id);
            if (existing == null) {
                throw new ValidationException(Reason.TRANSACTION_NOT_FOUND);
            }
            checkReferences(input, existing);
            apply(existing, input);
            existing.updatedAt = System.currentTimeMillis();
            db.transactionDao().update(existing);
            return null;
        });
    }

    /** @return true if a row was deleted, false if the id did not exist */
    @WorkerThread
    public boolean deleteSync(long id) {
        return db.transactionDao().deleteById(id) > 0;
    }

    @Nullable
    @WorkerThread
    public TransactionEntity getByIdSync(long id) {
        return db.transactionDao().getById(id);
    }

    /** Income and expense totals for one currency over {@code [startInclusive, endExclusive)}. */
    @NonNull
    @WorkerThread
    public Totals getTotalsSync(@NonNull String currency, long startInclusive, long endExclusive) {
        return db.runInTransaction(() -> new Totals(
                db.transactionDao().sumByType(TransactionType.INCOME, currency, startInclusive, endExclusive),
                db.transactionDao().sumByType(TransactionType.EXPENSE, currency, startInclusive, endExclusive)));
    }

    @NonNull
    @WorkerThread
    public Totals getAllTimeTotalsSync(@NonNull String currency) {
        return getTotalsSync(currency, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** Net balance (income minus expenses) of one currency over a range. */
    @WorkerThread
    public long getNetBalanceSync(@NonNull String currency, long startInclusive, long endExclusive) {
        return db.transactionDao().netBalance(currency, startInclusive, endExclusive);
    }

    @NonNull
    @WorkerThread
    public List<CategoryTotal> getTotalsByCategorySync(@NonNull TransactionType type,
                                                       @NonNull String currency,
                                                       long startInclusive, long endExclusive) {
        return db.transactionDao().totalsByCategory(type, currency, startInclusive, endExclusive);
    }

    @NonNull
    @WorkerThread
    public List<TransactionRow> getRecentSync(int limit) {
        return db.transactionDao().getRecent(limit);
    }

    // ---- Helpers ---------------------------------------------------------------------

    private static void apply(TransactionEntity entity, TransactionInput input) {
        entity.type = input.type;
        entity.amountMinor = input.amountMinor;
        entity.currencyCode = input.currencyCode;
        entity.categoryId = input.categoryId;
        entity.paymentMethodId = input.paymentMethodId;
        entity.transactionTimestamp = input.timestampMillis;
        entity.note = TransactionValidator.normalizeNote(input.note);
    }

    /**
     * The category must exist, match the type, and be active (an edit may keep the category it
     * already has even if that category was deactivated since). The payment method, if any, must exist.
     */
    private void checkReferences(TransactionInput input, @Nullable TransactionEntity existing)
            throws ValidationException {
        CategoryEntity category = db.categoryDao().getById(input.categoryId);
        if (category == null) {
            throw new ValidationException(Reason.CATEGORY_NOT_FOUND);
        }
        if (category.type != input.type) {
            throw new ValidationException(Reason.CATEGORY_TYPE_MISMATCH);
        }
        boolean unchanged = existing != null && existing.categoryId == input.categoryId;
        if (!category.isActive && !unchanged) {
            throw new ValidationException(Reason.CATEGORY_INACTIVE);
        }
        if (input.paymentMethodId != null
                && db.paymentMethodDao().getById(input.paymentMethodId) == null) {
            throw new ValidationException(Reason.PAYMENT_METHOD_NOT_FOUND);
        }
    }
}
