package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.List;

@Dao
public interface CategoryDao {

    /** Returns the new id, or -1 if a row with the same default_key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(CategoryEntity category);

    @Insert
    long insert(CategoryEntity category);

    @Update
    int update(CategoryEntity category);

    @Query("DELETE FROM categories WHERE id = :id")
    int deleteById(long id);

    @Nullable
    @Query("SELECT * FROM categories WHERE id = :id")
    CategoryEntity getById(long id);

    /** Categories offered in selectors: active ones of one type, in display order. */
    @Query("SELECT * FROM categories WHERE type = :type AND is_active = 1 ORDER BY sort_order, name")
    List<CategoryEntity> getActiveByType(TransactionType type);

    @Query("SELECT * FROM categories ORDER BY type, sort_order, name")
    List<CategoryEntity> getAll();

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM categories ORDER BY id")
    List<CategoryEntity> getAllById();

    /** Deletes every category; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM categories")
    void clearAll();

    @Query("SELECT COUNT(*) FROM categories WHERE default_key IS NOT NULL")
    int countDefaults();

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type AND LOWER(name) = LOWER(:name) AND id != :excludeId")
    int countByName(TransactionType type, String name, long excludeId);

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM categories WHERE type = :type")
    int maxSortOrder(TransactionType type);
}
