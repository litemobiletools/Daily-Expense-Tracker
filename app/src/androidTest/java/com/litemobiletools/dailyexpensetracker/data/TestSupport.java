package com.litemobiletools.dailyexpensetracker.data;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import java.util.Calendar;
import java.util.TimeZone;

/** Shared helpers for database tests. All data is made up. */
final class TestSupport {

    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    static final String USD = "USD";

    private TestSupport() {
    }

    static AppDatabase newDatabase() {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        DefaultDataSeeder.seed(db);
        return db;
    }

    /** Local (UTC in tests) time stamp. */
    static long at(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    static long day(int y, int m, int d) {
        return at(y, m, d, 0, 0);
    }

    static long categoryId(AppDatabase db, TransactionType type, String name) {
        for (CategoryEntity c : db.categoryDao().getAll()) {
            if (c.type == type && c.name.equals(name)) {
                return c.id;
            }
        }
        throw new AssertionError("No category " + name);
    }

    static long paymentId(AppDatabase db, String name) {
        for (PaymentMethodEntity p : db.paymentMethodDao().getActive()) {
            if (p.name.equals(name)) {
                return p.id;
            }
        }
        throw new AssertionError("No payment method " + name);
    }

    static long addTransaction(AppDatabase db, TransactionType type, long minor, String category,
                               String currency, long time) throws Exception {
        return new TransactionRepository(db).insertSync(new TransactionInput(type, minor, currency,
                categoryId(db, type, category), null, time, null));
    }

    static long expense(AppDatabase db, long minor, String category, long time) throws Exception {
        return addTransaction(db, TransactionType.EXPENSE, minor, category, USD, time);
    }

    static long income(AppDatabase db, long minor, String category, long time) throws Exception {
        return addTransaction(db, TransactionType.INCOME, minor, category, USD, time);
    }
}
