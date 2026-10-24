package com.litemobiletools.dailyexpensetracker.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/**
 * An income or expense category.
 *
 * <p>Transactions reference a category by {@link #id}, which is never reused. Renaming a
 * category keeps its id; deactivating it ({@link #isActive} = false) hides it from selectors
 * but keeps it for historical transactions. A category that transactions reference cannot be
 * deleted (the foreign key is RESTRICT).
 */
@Entity(tableName = "categories",
        indices = {
                @Index(value = "default_key", unique = true),
                @Index(value = {"type", "is_active", "sort_order"})
        })
public class CategoryEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String name = "";

    @NonNull
    public TransactionType type = TransactionType.EXPENSE;

    /** Identifier of a bundled icon, not a resource id (resource ids change between builds). */
    @NonNull
    @ColumnInfo(name = "icon_key")
    public String iconKey = "";

    /** Optional ARGB display color. */
    @Nullable
    @ColumnInfo(name = "color_argb")
    public Integer colorArgb;

    @ColumnInfo(name = "is_active")
    public boolean isActive = true;

    /** Stable key for built-in categories (null for user-created), used to seed idempotently. */
    @Nullable
    @ColumnInfo(name = "default_key")
    public String defaultKey;

    @ColumnInfo(name = "sort_order")
    public int sortOrder;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
