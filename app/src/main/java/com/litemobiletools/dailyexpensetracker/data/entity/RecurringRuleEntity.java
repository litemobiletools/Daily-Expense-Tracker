package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/**
 * A template that records a transaction on a schedule. It only creates records inside this app;
 * it never moves money. Occurrence number {@code n} (0-based) falls on a date computed from
 * {@link #startDate} and {@link #frequency} (see {@code Recurrence}); {@link #nextIndex} is the
 * first occurrence not yet handled, which is what keeps a refresh from recording anything twice.
 */
@Entity(tableName = "recurring_rules",
        foreignKeys = {
                @ForeignKey(entity = CategoryEntity.class,
                        parentColumns = "id", childColumns = "category_id",
                        onDelete = ForeignKey.RESTRICT),
                @ForeignKey(entity = PaymentMethodEntity.class,
                        parentColumns = "id", childColumns = "payment_method_id",
                        onDelete = ForeignKey.RESTRICT)
        },
        indices = {
                @Index("category_id"),
                @Index("payment_method_id"),
                @Index("is_active")
        })
public class RecurringRuleEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public TransactionType type = TransactionType.EXPENSE;

    @ColumnInfo(name = "amount_minor")
    public long amountMinor;

    @NonNull
    @ColumnInfo(name = "currency_code")
    public String currencyCode = "";

    @ColumnInfo(name = "category_id")
    public long categoryId;

    @Nullable
    @ColumnInfo(name = "payment_method_id")
    public Long paymentMethodId;

    @Nullable
    public String note;

    @NonNull
    public RecurrenceFrequency frequency = RecurrenceFrequency.MONTHLY;

    /** First moment of the local start day; also the anchor for day-of-month / month-and-day. */
    @ColumnInfo(name = "start_date")
    public long startDate;

    /** Optional last day (inclusive), first moment of that local day. */
    @Nullable
    @ColumnInfo(name = "end_date")
    public Long endDate;

    /** Index of the next occurrence that has not been recorded or skipped yet. */
    @ColumnInfo(name = "next_index")
    public long nextIndex;

    @ColumnInfo(name = "is_active")
    public boolean isActive = true;

    /** Occurrences that came due but were deliberately not recorded (missed while closed or paused). */
    @ColumnInfo(name = "skipped_count")
    public int skippedCount;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
