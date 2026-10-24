package com.litemobiletools.dailyexpensetracker.ui.recurring;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.RecurringRepository;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;

import java.util.TimeZone;

/** Display strings shared by the recurring list and editor. */
public final class RecurringLabels {

    private RecurringLabels() {
    }

    @StringRes
    public static int frequencyRes(@NonNull RecurrenceFrequency frequency) {
        switch (frequency) {
            case DAILY:
                return R.string.freq_daily;
            case WEEKLY:
                return R.string.freq_weekly;
            case YEARLY:
                return R.string.freq_yearly;
            default:
                return R.string.freq_monthly;
        }
    }

    public static String date(@NonNull Context context, long dayStart) {
        return DateUtils.formatDateTime(context, dayStart,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** Date without the year, for tight spaces such as list subtitles. */
    public static String shortDate(@NonNull Context context, long dayStart) {
        return DateUtils.formatDateTime(context, dayStart,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
    }

    /** "Paused", "Ended" or "Next: date". */
    @NonNull
    public static String status(@NonNull Context context, @NonNull RecurringRuleEntity rule) {
        if (!rule.isActive) {
            return context.getString(R.string.recurring_paused);
        }
        Long next = RecurringRepository.nextDue(rule, TimeZone.getDefault());
        return next == null ? context.getString(R.string.recurring_ended)
                : context.getString(R.string.recurring_next, shortDate(context, next));
    }
}
