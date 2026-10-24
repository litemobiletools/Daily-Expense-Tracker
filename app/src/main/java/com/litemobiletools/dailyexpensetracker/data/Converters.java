package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/** Stores {@link TransactionType} as its stable enum name ("INCOME" / "EXPENSE"). */
public final class Converters {

    private Converters() {
    }

    @TypeConverter
    @Nullable
    public static String fromType(@Nullable TransactionType type) {
        return type == null ? null : type.name();
    }

    @TypeConverter
    @Nullable
    public static TransactionType toType(@Nullable String name) {
        return name == null ? null : TransactionType.valueOf(name);
    }

    @TypeConverter
    @Nullable
    public static String fromPeriod(@Nullable BudgetPeriod period) {
        return period == null ? null : period.name();
    }

    @TypeConverter
    @Nullable
    public static BudgetPeriod toPeriod(@Nullable String name) {
        return name == null ? null : BudgetPeriod.valueOf(name);
    }

    @TypeConverter
    @Nullable
    public static String fromFrequency(@Nullable RecurrenceFrequency frequency) {
        return frequency == null ? null : frequency.name();
    }

    @TypeConverter
    @Nullable
    public static RecurrenceFrequency toFrequency(@Nullable String name) {
        return name == null ? null : RecurrenceFrequency.valueOf(name);
    }
}
