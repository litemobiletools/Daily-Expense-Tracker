package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TimeAmount;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.List;

/**
 * All range parameters are half-open: {@code startInclusive <= timestamp < endExclusive}.
 * Totals only ever combine rows of the single currency passed in.
 * Amounts are positive; the type decides the sign (see {@link TransactionEntity}).
 *
 * <p>Every query is parameterized; user text only ever travels as a bound argument.
 */
@Dao
public interface TransactionDao {

    String ROW_SELECT = "SELECT t.id AS id, t.type AS type, t.amount_minor AS amount_minor, "
            + "t.currency_code AS currency_code, t.transaction_timestamp AS transaction_timestamp, "
            + "t.note AS note, t.category_id AS category_id, c.name AS category_name, "
            + "c.icon_key AS category_icon_key, c.color_argb AS category_color_argb, "
            + "t.payment_method_id AS payment_method_id, p.name AS payment_method_name, "
            + "t.created_at AS created_at, t.updated_at AS updated_at "
            + "FROM transactions t INNER JOIN categories c ON c.id = t.category_id "
            + "LEFT JOIN payment_methods p ON p.id = t.payment_method_id ";

    /**
     * Optional filters: a NULL argument disables that condition. The amount bounds only match
     * rows in {@code :currency}. Search matches note, category name and payment method name
     * anywhere, and the transaction type (INCOME / EXPENSE) by prefix.
     */
    String HISTORY_WHERE = "WHERE (:type IS NULL OR t.type = :type) "
            + "AND (:categoryId IS NULL OR t.category_id = :categoryId) "
            + "AND (:paymentMethodId IS NULL OR t.payment_method_id = :paymentMethodId) "
            + "AND (:startInclusive IS NULL OR t.transaction_timestamp >= :startInclusive) "
            + "AND (:endExclusive IS NULL OR t.transaction_timestamp < :endExclusive) "
            + "AND ((:minAmount IS NULL AND :maxAmount IS NULL) OR t.currency_code = :currency) "
            + "AND (:minAmount IS NULL OR t.amount_minor >= :minAmount) "
            + "AND (:maxAmount IS NULL OR t.amount_minor <= :maxAmount) "
            + "AND (:pattern IS NULL "
            + "OR t.note LIKE :pattern ESCAPE '\\' "
            + "OR c.name LIKE :pattern ESCAPE '\\' "
            + "OR p.name LIKE :pattern ESCAPE '\\' "
            + "OR (:typePattern IS NOT NULL AND t.type LIKE :typePattern ESCAPE '\\')) ";

    @Insert
    long insert(TransactionEntity transaction);

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM transactions ORDER BY id")
    List<TransactionEntity> getAllEntities();

    /** Deletes every transaction; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM transactions")
    void clearAll();

    @Update
    int update(TransactionEntity transaction);

    @Query("DELETE FROM transactions WHERE id = :id")
    int deleteById(long id);

    @Nullable
    @Query("SELECT * FROM transactions WHERE id = :id")
    TransactionEntity getById(long id);

    @Nullable
    @Query(ROW_SELECT + "WHERE t.id = :id")
    TransactionRow getRowById(long id);

    @Query("SELECT COUNT(*) FROM transactions")
    int count();

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :categoryId")
    int countByCategory(long categoryId);

    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM transactions "
            + "WHERE type = :type AND currency_code = :currency "
            + "AND transaction_timestamp >= :startInclusive AND transaction_timestamp < :endExclusive")
    long sumByType(TransactionType type, String currency, long startInclusive, long endExclusive);

    /** Income minus expenses for one currency over a range. */
    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount_minor ELSE -amount_minor END), 0) "
            + "FROM transactions WHERE currency_code = :currency "
            + "AND transaction_timestamp >= :startInclusive AND transaction_timestamp < :endExclusive")
    long netBalance(String currency, long startInclusive, long endExclusive);

    /** Sum of one category's transactions of a type, in one currency, over {@code [start, end)}. */
    @Query("SELECT COALESCE(SUM(amount_minor), 0) FROM transactions "
            + "WHERE type = :type AND category_id = :categoryId AND currency_code = :currency "
            + "AND transaction_timestamp >= :startInclusive AND transaction_timestamp < :endExclusive")
    long sumByTypeAndCategory(TransactionType type, long categoryId, String currency,
                              long startInclusive, long endExclusive);

    /** Largest transactions of a type in a period, biggest first (newest first on ties). */
    @Query(ROW_SELECT + "WHERE t.type = :type AND t.currency_code = :currency "
            + "AND t.transaction_timestamp >= :startInclusive AND t.transaction_timestamp < :endExclusive "
            + "ORDER BY t.amount_minor DESC, t.transaction_timestamp DESC, t.id DESC LIMIT :limit")
    List<TransactionRow> largest(TransactionType type, String currency, long startInclusive,
                                 long endExclusive, int limit);

    /** Timestamps and amounts of a type in a period; the caller totals them per local day. */
    @Query("SELECT transaction_timestamp, amount_minor FROM transactions "
            + "WHERE type = :type AND currency_code = :currency "
            + "AND transaction_timestamp >= :startInclusive AND transaction_timestamp < :endExclusive")
    List<TimeAmount> timesAndAmounts(TransactionType type, String currency, long startInclusive,
                                     long endExclusive);

    @Query("SELECT c.id AS category_id, c.name AS category_name, SUM(t.amount_minor) AS total_minor, "
            + "COUNT(t.id) AS transaction_count "
            + "FROM transactions t INNER JOIN categories c ON c.id = t.category_id "
            + "WHERE t.type = :type AND t.currency_code = :currency "
            + "AND t.transaction_timestamp >= :startInclusive AND t.transaction_timestamp < :endExclusive "
            + "GROUP BY c.id, c.name ORDER BY total_minor DESC, c.name")
    List<CategoryTotal> totalsByCategory(TransactionType type, String currency,
                                         long startInclusive, long endExclusive);

    @Query(ROW_SELECT + "ORDER BY t.transaction_timestamp DESC, t.id DESC LIMIT :limit")
    List<TransactionRow> getRecent(int limit);

    /** Transactions in a period whose currency differs from {@code currency} (they are reported separately). */
    @Query("SELECT COUNT(*) FROM transactions WHERE currency_code != :currency "
            + "AND transaction_timestamp >= :startInclusive AND transaction_timestamp < :endExclusive")
    int countOtherCurrencies(String currency, long startInclusive, long endExclusive);

    String EXPORT_WHERE = "WHERE (:type IS NULL OR t.type = :type) "
            + "AND (:currency IS NULL OR t.currency_code = :currency) "
            + "AND (:startInclusive IS NULL OR t.transaction_timestamp >= :startInclusive) "
            + "AND (:endExclusive IS NULL OR t.transaction_timestamp < :endExclusive) ";

    /**
     * One page of an export, oldest first. Keyset paging (strictly after the last row's timestamp and
     * id) never skips or repeats a row, even if transactions are added while the export runs.
     */
    @Query(ROW_SELECT + EXPORT_WHERE
            + "AND (t.transaction_timestamp > :afterTimestamp "
            + "OR (t.transaction_timestamp = :afterTimestamp AND t.id > :afterId)) "
            + "ORDER BY t.transaction_timestamp, t.id LIMIT :limit")
    List<TransactionRow> exportPage(@Nullable TransactionType type, @Nullable String currency,
                                    @Nullable Long startInclusive, @Nullable Long endExclusive,
                                    long afterTimestamp, long afterId, int limit);

    @Query("SELECT COUNT(*) FROM transactions t " + EXPORT_WHERE)
    int exportCount(@Nullable TransactionType type, @Nullable String currency,
                    @Nullable Long startInclusive, @Nullable Long endExclusive);

    /** Newest first; ties on the timestamp are broken by id (newest id first) so paging is stable. */
    @Query(ROW_SELECT + HISTORY_WHERE
            + "ORDER BY t.transaction_timestamp DESC, t.id DESC LIMIT :limit OFFSET :offset")
    List<TransactionRow> queryHistory(@Nullable TransactionType type, @Nullable Long categoryId,
                                      @Nullable Long paymentMethodId, @Nullable Long startInclusive,
                                      @Nullable Long endExclusive, @Nullable Long minAmount,
                                      @Nullable Long maxAmount, String currency,
                                      @Nullable String pattern, @Nullable String typePattern,
                                      int limit, int offset);

    @Query("SELECT COUNT(*) FROM transactions t INNER JOIN categories c ON c.id = t.category_id "
            + "LEFT JOIN payment_methods p ON p.id = t.payment_method_id " + HISTORY_WHERE)
    int countHistory(@Nullable TransactionType type, @Nullable Long categoryId,
                     @Nullable Long paymentMethodId, @Nullable Long startInclusive,
                     @Nullable Long endExclusive, @Nullable Long minAmount,
                     @Nullable Long maxAmount, String currency,
                     @Nullable String pattern, @Nullable String typePattern);
}
