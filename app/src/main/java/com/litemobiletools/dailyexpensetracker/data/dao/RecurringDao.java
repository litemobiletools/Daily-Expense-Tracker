package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleRow;

import java.util.List;

@Dao
public interface RecurringDao {

    @Insert
    long insertRule(RecurringRuleEntity rule);

    @Update
    int updateRule(RecurringRuleEntity rule);

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    int deleteRule(long id);

    @Nullable
    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    RecurringRuleEntity getRule(long id);

    @Query("SELECT * FROM recurring_rules ORDER BY is_active DESC, id DESC")
    List<RecurringRuleEntity> listRules();

    @Query("SELECT r.*, c.name AS category_name, c.icon_key AS category_icon_key, "
            + "c.color_argb AS category_color_argb FROM recurring_rules r "
            + "INNER JOIN categories c ON c.id = r.category_id ORDER BY r.is_active DESC, r.id DESC")
    List<RecurringRuleRow> listRuleRows();

    @Query("SELECT * FROM recurring_rules WHERE is_active = 1")
    List<RecurringRuleEntity> listActiveRules();

    /** Read-all for the backup snapshot, including paused rules (worker thread only). */
    @Query("SELECT * FROM recurring_rules ORDER BY id")
    List<RecurringRuleEntity> listAll();

    /** Returns the new id, or -1 if this occurrence was already recorded (unique rule + date). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertOccurrenceIgnore(RecurringOccurrenceEntity occurrence);

    /**
     * Strict insert used only by the backup restore engine: the validated backup never contains
     * duplicate (rule, date) rows, so any conflict at insert time is a real error and must abort
     * the restore (the IGNORE variant above belongs to the recording flow, not to restore).
     */
    @Insert
    long insertOccurrence(RecurringOccurrenceEntity occurrence);

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM recurring_occurrences ORDER BY id")
    List<RecurringOccurrenceEntity> listAllOccurrences();

    /** Deletes every occurrence; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM recurring_occurrences")
    void clearAllOccurrences();

    /** Deletes every rule; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM recurring_rules")
    void clearAllRules();

    @Query("UPDATE recurring_occurrences SET transaction_id = :transactionId WHERE id = :occurrenceId")
    int linkTransaction(long occurrenceId, long transactionId);

    @Query("SELECT * FROM recurring_occurrences WHERE rule_id = :ruleId ORDER BY occurrence_date DESC LIMIT :limit")
    List<RecurringOccurrenceEntity> occurrences(long ruleId, int limit);

    @Query("SELECT COUNT(*) FROM recurring_rules WHERE category_id = :categoryId")
    int countRulesByCategory(long categoryId);

    @Query("SELECT COUNT(*) FROM recurring_occurrences WHERE rule_id = :ruleId")
    int occurrenceCount(long ruleId);

    @Query("SELECT COUNT(*) FROM recurring_occurrences WHERE rule_id = :ruleId AND occurrence_date = :date")
    int occurrenceExists(long ruleId, long date);
}
