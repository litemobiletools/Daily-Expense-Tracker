package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.io.Serializable;

/**
 * Search text plus filters for the transaction history. {@code null} means "no restriction".
 *
 * <p>The date range is stored as instants: {@link #startMillis} is the first moment of the
 * start day (inclusive) and {@link #endExclusiveMillis} is the first moment of the day AFTER
 * the end day, so every transaction on the selected end day is included. Amount bounds are in
 * minor units of the device currency and (when set) only match transactions in that currency.
 */
public final class TransactionFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    @Nullable public TransactionType type;
    @Nullable public Long categoryId;
    @Nullable public Long paymentMethodId;
    @Nullable public Long startMillis;
    @Nullable public Long endExclusiveMillis;
    @Nullable public Long minAmountMinor;
    @Nullable public Long maxAmountMinor;
    @NonNull public String searchText = "";

    @NonNull
    public TransactionFilter copy() {
        TransactionFilter copy = new TransactionFilter();
        copy.type = type;
        copy.categoryId = categoryId;
        copy.paymentMethodId = paymentMethodId;
        copy.startMillis = startMillis;
        copy.endExclusiveMillis = endExclusiveMillis;
        copy.minAmountMinor = minAmountMinor;
        copy.maxAmountMinor = maxAmountMinor;
        copy.searchText = searchText;
        return copy;
    }

    /** Number of active filters, not counting the search text. Date range and amount range count once each. */
    public int activeCount() {
        int count = 0;
        if (type != null) {
            count++;
        }
        if (categoryId != null) {
            count++;
        }
        if (paymentMethodId != null) {
            count++;
        }
        if (startMillis != null || endExclusiveMillis != null) {
            count++;
        }
        if (minAmountMinor != null || maxAmountMinor != null) {
            count++;
        }
        return count;
    }

    /** True if search text or any filter narrows the list. */
    public boolean hasCriteria() {
        return activeCount() > 0 || !searchText.trim().isEmpty();
    }

    /** Same filters, search text cleared. */
    @NonNull
    public TransactionFilter withoutSearch() {
        TransactionFilter copy = copy();
        copy.searchText = "";
        return copy;
    }
}
