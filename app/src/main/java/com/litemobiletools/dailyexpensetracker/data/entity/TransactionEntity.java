package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/**
 * One income or expense.
 *
 * <p><b>Money:</b> {@link #amountMinor} is an integer count of minor currency units
 * (cents for USD, whole yen for JPY, fils for KWD...), always <b>positive</b>. The number of
 * minor-unit digits comes from the ISO 4217 currency named by {@link #currencyCode}
 * ({@code java.util.Currency#getDefaultFractionDigits}), so 12.50 USD is stored as 1250 and
 * 500 JPY as 500. Whether a row adds to or subtracts from the balance is decided by
 * {@link #type}, never by the sign. No floating point is used, and no conversion between
 * currencies exists: totals only combine rows with the same currency code.
 *
 * <p><b>Time:</b> {@link #transactionTimestamp} is a UTC epoch-millisecond instant. Day and
 * month boundaries for queries are computed in the device's time zone and applied as
 * half-open ranges {@code [start, end)}.
 */
@Entity(tableName = "transactions",
        foreignKeys = {
                @ForeignKey(entity = CategoryEntity.class,
                        parentColumns = "id", childColumns = "category_id",
                        onDelete = ForeignKey.RESTRICT),
                @ForeignKey(entity = PaymentMethodEntity.class,
                        parentColumns = "id", childColumns = "payment_method_id",
                        onDelete = ForeignKey.RESTRICT)
        },
        indices = {
                @Index("transaction_timestamp"),
                @Index("category_id"),
                @Index("payment_method_id"),
                @Index(value = {"currency_code", "type", "transaction_timestamp"})
        })
public class TransactionEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public TransactionType type = TransactionType.EXPENSE;

    /** Positive amount in minor currency units. */
    @ColumnInfo(name = "amount_minor")
    public long amountMinor;

    /** ISO 4217 code, e.g. "USD". */
    @NonNull
    @ColumnInfo(name = "currency_code")
    public String currencyCode = "";

    @ColumnInfo(name = "category_id")
    public long categoryId;

    @Nullable
    @ColumnInfo(name = "payment_method_id")
    public Long paymentMethodId;

    @ColumnInfo(name = "transaction_timestamp")
    public long transactionTimestamp;

    @Nullable
    public String note;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
