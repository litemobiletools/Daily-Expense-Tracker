package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Proof that one scheduled occurrence of a rule has been recorded. The unique index on
 * (rule_id, occurrence_date) makes recording idempotent at the database level. The link to the
 * created transaction is SET NULL on delete, so deleting that transaction does not make the
 * occurrence eligible to be recorded again.
 */
@Entity(tableName = "recurring_occurrences",
        foreignKeys = {
                @ForeignKey(entity = RecurringRuleEntity.class,
                        parentColumns = "id", childColumns = "rule_id",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = TransactionEntity.class,
                        parentColumns = "id", childColumns = "transaction_id",
                        onDelete = ForeignKey.SET_NULL)
        },
        indices = {
                @Index(value = {"rule_id", "occurrence_date"}, unique = true),
                @Index("transaction_id")
        })
public class RecurringOccurrenceEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "rule_id")
    public long ruleId;

    /** First moment of the local day the occurrence was scheduled for. */
    @ColumnInfo(name = "occurrence_date")
    public long occurrenceDate;

    @Nullable
    @ColumnInfo(name = "transaction_id")
    public Long transactionId;

    @ColumnInfo(name = "created_at")
    public long createdAt;
}
