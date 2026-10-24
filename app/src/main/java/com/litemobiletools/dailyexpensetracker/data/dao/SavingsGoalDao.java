package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;

import java.util.List;

@Dao
public interface SavingsGoalDao {

    String WITH_SAVED = "SELECT g.*, COALESCE((SELECT SUM(c.delta_minor) FROM goal_contributions c "
            + "WHERE c.goal_id = g.id), 0) AS saved_minor FROM savings_goals g ";

    @Insert
    long insert(SavingsGoalEntity goal);

    @Update
    int update(SavingsGoalEntity goal);

    @Query("DELETE FROM savings_goals WHERE id = :id")
    int deleteById(long id);

    @Nullable
    @Query("SELECT * FROM savings_goals WHERE id = :id")
    SavingsGoalEntity getById(long id);

    @Nullable
    @Query(WITH_SAVED + "WHERE g.id = :id")
    GoalWithSaved getWithSaved(long id);

    /** Open goals first, then completed ones; newest first within each group. */
    @Query(WITH_SAVED + "ORDER BY g.is_completed, g.created_at DESC, g.id DESC")
    List<GoalWithSaved> listWithSaved();

    @Query("SELECT COALESCE(SUM(delta_minor), 0) FROM goal_contributions WHERE goal_id = :goalId")
    long savedMinor(long goalId);

    @Insert
    long insertContribution(GoalContributionEntity contribution);

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM savings_goals ORDER BY id")
    List<SavingsGoalEntity> getAll();

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM goal_contributions ORDER BY id")
    List<GoalContributionEntity> getAllContributions();

    /** Deletes every contribution; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM goal_contributions")
    void clearAllContributions();

    /** Deletes every goal; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM savings_goals")
    void clearAll();

    @Query("SELECT * FROM goal_contributions WHERE goal_id = :goalId "
            + "ORDER BY contributed_at DESC, id DESC LIMIT :limit")
    List<GoalContributionEntity> contributions(long goalId, int limit);

    @Query("SELECT COUNT(*) FROM goal_contributions WHERE goal_id = :goalId")
    int contributionCount(long goalId);
}
