package com.litemobiletools.dailyexpensetracker.data.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;

import java.util.List;

public class PaymentMethodRepository {

    private final AppDatabase db;

    public PaymentMethodRepository(@NonNull AppDatabase db) {
        this.db = db;
    }

    public static PaymentMethodRepository get(@NonNull Context context) {
        return new PaymentMethodRepository(AppDatabase.getInstance(context));
    }

    public void getActive(@NonNull RepoCallback<List<PaymentMethodEntity>> callback) {
        DatabaseExecutor.run(this::getActiveSync, callback);
    }

    @NonNull
    @WorkerThread
    public List<PaymentMethodEntity> getActiveSync() {
        return db.paymentMethodDao().getActive();
    }
}
