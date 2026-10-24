package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.ValidationException;
import com.litemobiletools.dailyexpensetracker.data.ValidationException.Reason;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.List;

/**
 * Category operations. A category referenced by any transaction or recurring rule can only be
 * deactivated, never deleted, so history and schedules keep pointing at a real category.
 * (Budgets for a deleted category are removed with it.)
 */
public class CategoryRepository {

    public static final int MAX_NAME_LENGTH = 30;

    private final AppDatabase db;

    public CategoryRepository(@NonNull AppDatabase db) {
        this.db = db;
    }

    public static CategoryRepository get(@NonNull Context context) {
        return new CategoryRepository(AppDatabase.getInstance(context));
    }

    public void getActiveByType(@NonNull TransactionType type,
                                @NonNull RepoCallback<List<CategoryEntity>> callback) {
        DatabaseExecutor.run(() -> getActiveByTypeSync(type), callback);
    }

    /** Every category, active or not (history keeps showing deactivated ones). */
    public void getAll(@NonNull RepoCallback<List<CategoryEntity>> callback) {
        DatabaseExecutor.run(() -> db.categoryDao().getAll(), callback);
    }

    /**
     * Categories for the form: the active ones of {@code type}, plus {@code includeId} if it is a
     * deactivated category of that type (so editing an old transaction can keep its category).
     */
    public void getSelectableByType(@NonNull TransactionType type, long includeId,
                                    @NonNull RepoCallback<List<CategoryEntity>> callback) {
        DatabaseExecutor.run(() -> getSelectableByTypeSync(type, includeId), callback);
    }

    @NonNull
    @WorkerThread
    public List<CategoryEntity> getSelectableByTypeSync(@NonNull TransactionType type, long includeId) {
        List<CategoryEntity> list = new java.util.ArrayList<>(db.categoryDao().getActiveByType(type));
        if (includeId > 0) {
            boolean present = false;
            for (CategoryEntity c : list) {
                present |= c.id == includeId;
            }
            CategoryEntity extra = present ? null : db.categoryDao().getById(includeId);
            if (extra != null && extra.type == type) {
                list.add(extra);
            }
        }
        return list;
    }

    @NonNull
    @WorkerThread
    public List<CategoryEntity> getActiveByTypeSync(@NonNull TransactionType type) {
        return db.categoryDao().getActiveByType(type);
    }

    @Nullable
    @WorkerThread
    public CategoryEntity getByIdSync(long id) {
        return db.categoryDao().getById(id);
    }

    /** @return the new category id */
    @WorkerThread
    public long addSync(@NonNull String name, @NonNull TransactionType type,
                        @NonNull String iconKey, @Nullable Integer colorArgb) throws Exception {
        String clean = cleanName(name);
        return db.runInTransaction(() -> {
            if (db.categoryDao().countByName(type, clean, 0) > 0) {
                throw new ValidationException(Reason.CATEGORY_NAME_DUPLICATE);
            }
            long now = System.currentTimeMillis();
            CategoryEntity category = new CategoryEntity();
            category.name = clean;
            category.type = type;
            category.iconKey = iconKey;
            category.colorArgb = colorArgb;
            category.sortOrder = db.categoryDao().maxSortOrder(type) + 1;
            category.createdAt = now;
            category.updatedAt = now;
            return db.categoryDao().insert(category);
        });
    }

    /** Renames a category; its id (and therefore all linked history) is unchanged. */
    @WorkerThread
    public void renameSync(long id, @NonNull String newName) throws Exception {
        String clean = cleanName(newName);
        db.runInTransaction(() -> {
            CategoryEntity category = requireCategory(id);
            if (db.categoryDao().countByName(category.type, clean, id) > 0) {
                throw new ValidationException(Reason.CATEGORY_NAME_DUPLICATE);
            }
            category.name = clean;
            category.updatedAt = System.currentTimeMillis();
            db.categoryDao().update(category);
            return null;
        });
    }

    /** Hides (or restores) a category in selectors without touching existing transactions. */
    @WorkerThread
    public void setActiveSync(long id, boolean active) throws Exception {
        db.runInTransaction(() -> {
            CategoryEntity category = requireCategory(id);
            category.isActive = active;
            category.updatedAt = System.currentTimeMillis();
            db.categoryDao().update(category);
            return null;
        });
    }

    /**
     * Deletes a category only if no transaction references it.
     *
     * @throws ValidationException with {@link Reason#CATEGORY_IN_USE} otherwise
     */
    @WorkerThread
    public void deleteSync(long id) throws Exception {
        db.runInTransaction(() -> {
            requireCategory(id);
            if (db.transactionDao().countByCategory(id) > 0 || db.recurringDao().countRulesByCategory(id) > 0) {
                throw new ValidationException(Reason.CATEGORY_IN_USE);
            }
            db.categoryDao().deleteById(id);
            return null;
        });
    }

    private CategoryEntity requireCategory(long id) throws ValidationException {
        CategoryEntity category = db.categoryDao().getById(id);
        if (category == null) {
            throw new ValidationException(Reason.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private static String cleanName(String name) throws ValidationException {
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME_LENGTH) {
            throw new ValidationException(Reason.CATEGORY_NAME_INVALID);
        }
        return clean;
    }
}
