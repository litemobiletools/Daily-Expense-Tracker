package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;

import java.util.List;

@Dao
public interface BudgetDao {

    @Insert
    long insert(BudgetEntity budget);

    @Update
    int update(BudgetEntity budget);

    @Query("DELETE FROM budgets WHERE id = :id")
    int deleteById(long id);

    @Nullable
    @Query("SELECT * FROM budgets WHERE id = :id")
    BudgetEntity getById(long id);

    /** Overall budget first, then category budgets. */
    @Query("SELECT * FROM budgets WHERE period = :period AND currency_code = :currency "
            + "ORDER BY (category_id IS NOT NULL), id")
    List<BudgetEntity> listByPeriod(BudgetPeriod period, String currency);

    @Query("SELECT COUNT(*) FROM budgets")
    int count();

    @Query("SELECT * FROM budgets ORDER BY id")
    List<BudgetEntity> getAll();

    /** Deletes every budget; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM budgets")
    void clearAll();

    /** The budget for this exact (period, category, currency), if any. A null category means the overall budget. */
    @Nullable
    @Query("SELECT * FROM budgets WHERE period = :period AND currency_code = :currency "
            + "AND ((:categoryId IS NULL AND category_id IS NULL) OR category_id = :categoryId) LIMIT 1")
    BudgetEntity find(BudgetPeriod period, @Nullable Long categoryId, String currency);
}
