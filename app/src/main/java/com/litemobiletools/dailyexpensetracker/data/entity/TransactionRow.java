package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.Objects;

/**
 * Query result: a transaction joined with its category and (optional) payment method, ready
 * to display in lists and the details sheet. Read-only; edits go through {@link TransactionEntity}.
 */
public class TransactionRow {

    public long id;

    @NonNull
    public TransactionType type = TransactionType.EXPENSE;

    @ColumnInfo(name = "amount_minor")
    public long amountMinor;

    @NonNull
    @ColumnInfo(name = "currency_code")
    public String currencyCode = "";

    @ColumnInfo(name = "transaction_timestamp")
    public long transactionTimestamp;

    @Nullable
    public String note;

    @ColumnInfo(name = "category_id")
    public long categoryId;

    @NonNull
    @ColumnInfo(name = "category_name")
    public String categoryName = "";

    @Nullable
    @ColumnInfo(name = "category_icon_key")
    public String categoryIconKey;

    @Nullable
    @ColumnInfo(name = "category_color_argb")
    public Integer categoryColorArgb;

    @Nullable
    @ColumnInfo(name = "payment_method_id")
    public Long paymentMethodId;

    @Nullable
    @ColumnInfo(name = "payment_method_name")
    public String paymentMethodName;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    /** True if everything that is displayed for this row is identical (used by list diffing). */
    public boolean sameContentAs(@NonNull TransactionRow other) {
        return id == other.id
                && type == other.type
                && amountMinor == other.amountMinor
                && currencyCode.equals(other.currencyCode)
                && transactionTimestamp == other.transactionTimestamp
                && Objects.equals(note, other.note)
                && categoryId == other.categoryId
                && categoryName.equals(other.categoryName)
                && Objects.equals(categoryIconKey, other.categoryIconKey)
                && Objects.equals(categoryColorArgb, other.categoryColorArgb)
                && Objects.equals(paymentMethodId, other.paymentMethodId)
                && Objects.equals(paymentMethodName, other.paymentMethodName)
                && updatedAt == other.updatedAt;
    }
}
