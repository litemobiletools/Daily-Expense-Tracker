package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/** User-supplied values for creating or editing a transaction, before validation. */
public final class TransactionInput {

    @Nullable public final TransactionType type;
    public final long amountMinor;
    @NonNull public final String currencyCode;
    public final long categoryId;
    @Nullable public final Long paymentMethodId;
    public final long timestampMillis;
    @Nullable public final String note;

    public TransactionInput(@Nullable TransactionType type, long amountMinor,
                            @NonNull String currencyCode, long categoryId,
                            @Nullable Long paymentMethodId, long timestampMillis,
                            @Nullable String note) {
        this.type = type;
        this.amountMinor = amountMinor;
        this.currencyCode = currencyCode;
        this.categoryId = categoryId;
        this.paymentMethodId = paymentMethodId;
        this.timestampMillis = timestampMillis;
        this.note = note;
    }
}
