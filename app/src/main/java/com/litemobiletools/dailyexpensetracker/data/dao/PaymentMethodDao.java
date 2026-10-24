package com.litemobiletools.dailyexpensetracker.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;

import java.util.List;

@Dao
public interface PaymentMethodDao {

    /** Returns the new id, or -1 if a row with the same default_key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(PaymentMethodEntity method);

    @Insert
    long insert(PaymentMethodEntity method);

    @Nullable
    @Query("SELECT * FROM payment_methods WHERE id = :id")
    PaymentMethodEntity getById(long id);

    @Query("SELECT * FROM payment_methods WHERE is_active = 1 ORDER BY sort_order, name")
    List<PaymentMethodEntity> getActive();

    @Query("SELECT COUNT(*) FROM payment_methods WHERE default_key IS NOT NULL")
    int countDefaults();

    /** Read-all for the backup snapshot (worker thread only). */
    @Query("SELECT * FROM payment_methods ORDER BY id")
    List<PaymentMethodEntity> getAll();

    /** Deletes every payment method; only the restore engine calls this inside its transaction. */
    @Query("DELETE FROM payment_methods")
    void clearAll();
}
