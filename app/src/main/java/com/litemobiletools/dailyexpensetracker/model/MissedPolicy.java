package com.litemobiletools.dailyexpensetracker.model;

/** What to do with recurring occurrences that came due while the app was not opened. */
public enum MissedPolicy {
    /** Record missed occurrences, but at most the most recent {@code RecurringRepository.MAX_CATCH_UP} per rule. */
    CATCH_UP,
    /** Record only an occurrence that is due today; missed earlier ones are skipped (and counted). */
    SKIP_MISSED
}
