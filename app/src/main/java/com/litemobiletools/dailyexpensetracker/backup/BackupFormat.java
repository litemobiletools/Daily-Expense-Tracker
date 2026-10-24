package com.litemobiletools.dailyexpensetracker.backup;

/**
 * Fixed facts about the versioned backup file format: one line of header JSON, a newline
 * separator, then the data JSON bytes. The header states the format id and version, the
 * metadata, per-table record counts and a SHA-256 over the exact data bytes that follow.
 */
public final class BackupFormat {

    /** Marker at the start of every backup header. */
    public static final String FORMAT_ID = "det-backup";

    /**
     * Current format version. Restoring a file with a higher version is rejected ("made by a
     * newer version of the app"); a lower one is accepted only through explicit support in the
     * file reader, so no data is guessed.
     */
    public static final int FORMAT_VERSION = 1;

    /** Only hash algorithm supported by this format. */
    public static final String HASH_ALGORITHM = "SHA-256";

    /** Byte separating the (single-line) header from the data bytes. */
    public static final byte HEADER_SEPARATOR = ('\n');

    /** Data sections of the backup, in restore (foreign-key-safe insert) order. */
    public static final String TABLE_CATEGORIES = "categories";
    public static final String TABLE_PAYMENT_METHODS = "paymentMethods";
    public static final String TABLE_TRANSACTIONS = "transactions";
    public static final String TABLE_BUDGETS = "budgets";
    public static final String TABLE_SAVINGS_GOALS = "savingsGoals";
    public static final String TABLE_GOAL_CONTRIBUTIONS = "goalContributions";
    public static final String TABLE_RECURRING_RULES = "recurringRules";
    public static final String TABLE_RECURRING_OCCURRENCES = "recurringOccurrences";
    public static final String TABLE_PREFERENCES = "preferences";

    /** All data keys the file must contain, including the preferences object. */
    public static final String[] ALL_TABLES = {
            TABLE_CATEGORIES, TABLE_PAYMENT_METHODS, TABLE_TRANSACTIONS, TABLE_BUDGETS,
            TABLE_SAVINGS_GOALS, TABLE_GOAL_CONTRIBUTIONS, TABLE_RECURRING_RULES,
            TABLE_RECURRING_OCCURRENCES, TABLE_PREFERENCES};

    private BackupFormat() {
    }
}
