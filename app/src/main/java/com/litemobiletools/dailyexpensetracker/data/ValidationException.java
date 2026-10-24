package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;

/** Input rejected before anything was written. */
public class ValidationException extends Exception {

    public enum Reason {
        INVALID_TYPE,
        AMOUNT_NOT_POSITIVE,
        AMOUNT_TOO_LARGE,
        INVALID_CURRENCY,
        NOTE_TOO_LONG,
        INVALID_DATE,
        CATEGORY_NOT_FOUND,
        CATEGORY_TYPE_MISMATCH,
        CATEGORY_INACTIVE,
        CATEGORY_IN_USE,
        CATEGORY_NAME_INVALID,
        CATEGORY_NAME_DUPLICATE,
        PAYMENT_METHOD_NOT_FOUND,
        TRANSACTION_NOT_FOUND,
        BUDGET_DUPLICATE,
        BUDGET_NOT_FOUND,
        BUDGET_CATEGORY_INVALID,
        GOAL_NOT_FOUND,
        GOAL_NAME_INVALID,
        WITHDRAWAL_EXCEEDS_SAVED,
        RULE_NOT_FOUND,
        END_BEFORE_START
    }

    @NonNull private final Reason reason;

    public ValidationException(@NonNull Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    @NonNull
    public Reason getReason() {
        return reason;
    }
}
