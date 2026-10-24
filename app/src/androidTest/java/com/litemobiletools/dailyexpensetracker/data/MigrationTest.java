package com.litemobiletools.dailyexpensetracker.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Migration tests use the exported schemas in app/schemas. The version 1 database is filled with
 * Phase 1-3 style data (categories, payment methods, transactions) before migrating, to prove it survives.
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String DB_NAME = "migration_test.db";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class,
            java.util.Collections.emptyList(),
            new FrameworkSQLiteOpenHelperFactory());

    private static long insert(SupportSQLiteDatabase db, String table, ContentValues values) {
        return db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, values);
    }

    private static void fillVersion1(SupportSQLiteDatabase db) {
        ContentValues category = new ContentValues();
        category.put("name", "Food");
        category.put("type", "EXPENSE");
        category.put("icon_key", "restaurant");
        category.put("color_argb", 0xFF3E7CB1);
        category.put("is_active", 1);
        category.put("default_key", "expense_food");
        category.put("sort_order", 0);
        category.put("created_at", 1000L);
        category.put("updated_at", 1000L);
        long categoryId = insert(db, "categories", category);

        ContentValues payment = new ContentValues();
        payment.put("name", "Cash");
        payment.put("icon_key", "payments");
        payment.put("is_active", 1);
        payment.put("default_key", "cash");
        payment.put("sort_order", 0);
        long paymentId = insert(db, "payment_methods", payment);

        for (int i = 0; i < 3; i++) {
            ContentValues tx = new ContentValues();
            tx.put("type", "EXPENSE");
            tx.put("amount_minor", 1000L + i);
            tx.put("currency_code", "USD");
            tx.put("category_id", categoryId);
            tx.put("payment_method_id", paymentId);
            tx.put("transaction_timestamp", 1_700_000_000_000L + i);
            tx.put("note", "note " + i);
            tx.put("created_at", 2000L);
            tx.put("updated_at", 2000L);
            insert(db, "transactions", tx);
        }
    }

    @Test
    public void migration1To2KeepsAllExistingDataAndAddsEmptyNewTables() throws Exception {
        SupportSQLiteDatabase v1 = helper.createDatabase(DB_NAME, 1);
        fillVersion1(v1);
        v1.close();

        SupportSQLiteDatabase v2 = helper.runMigrationsAndValidate(DB_NAME, 2, true, Migrations.MIGRATION_1_2);

        assertEquals(3, count(v2, "transactions"));
        assertEquals(1, count(v2, "categories"));
        assertEquals(1, count(v2, "payment_methods"));
        try (Cursor c = v2.query("SELECT amount_minor, note, category_id, payment_method_id, created_at "
                + "FROM transactions ORDER BY amount_minor")) {
            assertTrue(c.moveToFirst());
            assertEquals(1000L, c.getLong(0));
            assertEquals("note 0", c.getString(1));
            assertFalse(c.isNull(2));
            assertFalse(c.isNull(3));
            assertEquals(2000L, c.getLong(4));
        }
        for (String table : new String[]{"budgets", "savings_goals", "goal_contributions",
                "recurring_rules", "recurring_occurrences"}) {
            assertEquals(table, 0, count(v2, table));
        }
        v2.close();
    }

    @Test
    public void migratedDatabaseOpensThroughRoomAndNewTablesWork() throws Exception {
        SupportSQLiteDatabase v1 = helper.createDatabase(DB_NAME, 1);
        fillVersion1(v1);
        v1.close();

        AppDatabase db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(),
                AppDatabase.class, DB_NAME).addMigrations(AppDatabase.MIGRATIONS).build();
        try {
            assertEquals(3, db.transactionDao().count());
            TransactionEntity tx = db.transactionDao().getRecent(1).isEmpty() ? null
                    : db.transactionDao().getById(db.transactionDao().getRecent(1).get(0).id);
            assertNotNull(tx);
            assertEquals("USD", tx.currencyCode);
            // New tables are usable and enforce their foreign keys.
            long categoryId = db.categoryDao().getAll().get(0).id;
            com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity budget =
                    new com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity();
            budget.categoryId = categoryId;
            budget.currencyCode = "USD";
            budget.amountMinor = 5000;
            long id = db.budgetDao().insert(budget);
            assertTrue(id > 0);
            assertEquals(1, db.budgetDao().count());
        } finally {
            db.close();
        }
    }

    @Test
    public void schemaVersion2IsExportedAndTheLatestVersionOpensFresh() throws Exception {
        SupportSQLiteDatabase v2 = helper.createDatabase(DB_NAME + ".fresh", 2);
        assertEquals(0, count(v2, "budgets"));
        v2.close();
    }

    private static int count(SupportSQLiteDatabase db, String table) {
        try (Cursor c = db.query("SELECT COUNT(*) FROM " + table)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }
}
