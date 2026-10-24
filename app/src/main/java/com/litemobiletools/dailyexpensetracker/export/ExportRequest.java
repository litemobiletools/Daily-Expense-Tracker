package com.litemobiletools.dailyexpensetracker.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** What the user asked to export. Survives configuration changes while the file picker is open. */
public final class ExportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        TRANSACTIONS_CSV,
        CATEGORY_CSV,
        BUDGET_CSV,
        PDF
    }

    @NonNull public final Kind kind;
    @NonNull public final ReportRange range;
    @NonNull public final String currencyCode;
    /** Transactions CSV only: ignore the range and export everything. */
    public final boolean allTime;
    /** Transactions CSV only: null for both income and expenses. */
    @Nullable public final TransactionType type;
    /** PDF only: append the transaction table. */
    public final boolean includeTransactions;

    public ExportRequest(@NonNull Kind kind, @NonNull ReportRange range, @NonNull String currencyCode,
                         boolean allTime, @Nullable TransactionType type, boolean includeTransactions) {
        this.kind = kind;
        this.range = range;
        this.currencyCode = currencyCode;
        this.allTime = allTime;
        this.type = type;
        this.includeTransactions = includeTransactions;
    }

    @NonNull
    public String mimeType() {
        return kind == Kind.PDF ? "application/pdf" : "text/csv";
    }

    /** File name suggested in the system save dialog, e.g. {@code expenses_2026-10-01_to_2026-10-31.csv}. */
    @NonNull
    public String suggestedName(@NonNull TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(zone);
        String span = f.format(new Date(range.start)) + "_to_" + f.format(new Date(range.lastInstant()));
        switch (kind) {
            case TRANSACTIONS_CSV: {
                String what = type == TransactionType.INCOME ? "income"
                        : type == TransactionType.EXPENSE ? "expenses" : "transactions";
                return what + "_" + (allTime ? "all_time" : span) + ".csv";
            }
            case CATEGORY_CSV:
                return "category_summary_" + span + ".csv";
            case BUDGET_CSV:
                return "budget_summary_" + span + ".csv";
            default:
                return "financial_report_" + span + ".pdf";
        }
    }
}
