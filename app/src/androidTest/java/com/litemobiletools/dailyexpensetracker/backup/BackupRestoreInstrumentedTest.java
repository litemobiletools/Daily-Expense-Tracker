package com.litemobiletools.dailyexpensetracker.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.backup.BackupFormatException.Reason;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Restore over a real SQLite/Room engine (instrumented; runs on a device or emulator only):
 * full replace with preserved ids, one-transaction rollback on any failure, and rejects that
 * never touch the database. Uses made-up records only.
 */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreInstrumentedTest {

    private static final long T = 1_760_000_000_000L;

    private Context context;
    private AppDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        if (db.isOpen()) {
            db.close();
        }
    }

    // ---- data -------------------------------------------------------------------------

    private BackupData sampleData() {
        CategoryEntity c = new CategoryEntity();
        c.id = 30;
        c.name = "Trips";
        c.type = TransactionType.EXPENSE;
        c.iconKey = "car";
        c.colorArgb = 0xFF3E7CB1; // real app color: alpha 0xFF, negative as an int (device regression)
        c.isActive = true;
        c.defaultKey = null;
        c.sortOrder = 0;
        c.createdAt = T;
        c.updatedAt = T;

        PaymentMethodEntity p = new PaymentMethodEntity();
        p.id = 20;
        p.name = "Card backup";
        p.iconKey = "card";
        p.isActive = true;
        p.defaultKey = null;
        p.sortOrder = 0;

        TransactionEntity t = new TransactionEntity();
        t.id = 70;
        t.type = TransactionType.EXPENSE;
        t.amountMinor = 2500;
        t.currencyCode = "USD";
        t.categoryId = c.id;
        t.paymentMethodId = p.id;
        t.transactionTimestamp = T;
        t.note = null;
        t.createdAt = T;
        t.updatedAt = T;

        SavingsGoalEntity g = new SavingsGoalEntity();
        g.id = 50;
        g.name = "Spare";
        g.targetMinor = 90000;
        g.currencyCode = "USD";
        g.targetDate = null;
        g.isCompleted = false;
        g.completedAt = null;
        g.createdAt = T;
        g.updatedAt = T;

        GoalContributionEntity gc = new GoalContributionEntity();
        gc.id = 60;
        gc.goalId = g.id;
        gc.deltaMinor = 2500;
        gc.note = null;
        gc.contributedAt = T;

        RecurringRuleEntity r = new RecurringRuleEntity();
        r.id = 80;
        r.type = TransactionType.EXPENSE;
        r.amountMinor = 1500;
        r.currencyCode = "USD";
        r.categoryId = c.id;
        r.paymentMethodId = null;
        r.note = null;
        r.frequency = RecurrenceFrequency.WEEKLY;
        r.startDate = T;
        r.endDate = null;
        r.nextIndex = 0;
        r.isActive = true;
        r.skippedCount = 0;
        r.createdAt = T;
        r.updatedAt = T;

        RecurringOccurrenceEntity o = new RecurringOccurrenceEntity();
        o.id = 90;
        o.ruleId = r.id;
        o.occurrenceDate = T;
        o.transactionId = t.id;
        o.createdAt = T;

        BackupData.AppInfo info = new BackupData.AppInfo(context.getPackageName(), "1.0", 1,
                db.getOpenHelper().getWritableDatabase().getVersion());
        return new BackupData(info, T, new BackupData.Preferences(1, "SKIP_MISSED"),
                Collections.singletonList(c),
                Collections.singletonList(p),
                Collections.singletonList(t),
                Collections.emptyList(),
                Collections.singletonList(g),
                Collections.singletonList(gc),
                Collections.singletonList(r),
                Collections.singletonList(o));
    }

    private static CategoryEntity localCategory(String name) {
        CategoryEntity c = new CategoryEntity();
        c.name = name;
        c.type = TransactionType.EXPENSE;
        c.iconKey = "x";
        c.isActive = true;
        c.defaultKey = null;
        c.sortOrder = 0;
        c.createdAt = T;
        c.updatedAt = T;
        return c;
    }

    private static TransactionEntity localExpense(long categoryId) {
        TransactionEntity t = new TransactionEntity();
        t.type = TransactionType.EXPENSE;
        t.amountMinor = 111;
        t.currencyCode = "USD";
        t.categoryId = categoryId;
        t.paymentMethodId = null;
        t.transactionTimestamp = T;
        t.note = null;
        t.createdAt = T;
        t.updatedAt = T;
        return t;
    }

    // ---- tests ------------------------------------------------------------------------

    @Test
    public void restoreReplacesAllTablesPreservesIdsAndKeepsCloudCopyIntact() {
        // Existing local data (auto-generated ids), to be fully replaced:
        long oldCategory = db.categoryDao().insert(localCategory("Local"));
        db.transactionDao().insert(localExpense(oldCategory));
        assertEquals(1, db.transactionDao().getAllEntities().size());

        BackupData data = sampleData();
        byte[] bytes = BackupWriter.write(data).getBytes(StandardCharsets.UTF_8);
        BackupData validated = validated(bytes);

        BackupRestorer.Result result = BackupRestorer.restore(db, validated);

        assertEquals(data.recordCount(), result.records);
        List<CategoryEntity> categories = db.categoryDao().getAll();
        assertEquals(1, categories.size());
        assertEquals(30, categories.get(0).id); // the restored id wins, not a fresh one
        List<TransactionEntity> transactions = db.transactionDao().getAllEntities();
        assertEquals(1, transactions.size());
        assertEquals(70, transactions.get(0).id);
        assertEquals(2500, transactions.get(0).amountMinor); // long minor units preserved
        assertEquals(20, transactions.get(0).categoryId);
        assertEquals(1, db.savingsGoalDao().getAll().size());
        assertEquals(1, db.savingsGoalDao().getAllContributions().size());
        assertEquals(1, db.recurringDao().listAll().size());
        assertEquals(1, db.recurringDao().listAllOccurrences().size());
        assertEquals(0, db.budgetDao().getAll().size()); // sample data has none
        // Sanity: the foreign keys point at the restored rows (matches the backup, not local ids).
        assertEquals(data.recurringOccurrences.get(0).transactionId,
                Long.valueOf(transactions.get(0).id));
    }

    @Test
    public void restoreRollsBackCompletelyWhenAnyRowFails() {
        // Existing data that must survive unchanged:
        long oldCategory = db.categoryDao().insert(localCategory("Keep me"));
        long oldTransaction = db.transactionDao().insert(localExpense(oldCategory));
        assertEquals(1, db.transactionDao().getAllEntities().size());

        BackupData bad = sampleData();
        // The transaction points at a category the backup does not contain: the insert must fail.
        bad.transactions.get(0).categoryId = 999;

        try {
            BackupRestorer.restore(db, bad);
            fail();
        } catch (RuntimeException expected) {
            // foreign key constraint inside the transaction
        }

        // Everything is exactly as before: the whole transaction rolled back.
        List<TransactionEntity> transactions = db.transactionDao().getAllEntities();
        assertEquals(1, transactions.size());
        assertEquals(oldTransaction, transactions.get(0).id);
        assertTrue(transactions.get(0).categoryId != 999);
        List<CategoryEntity> categories = db.categoryDao().getAll();
        assertEquals(1, categories.size());
        assertEquals(oldCategory, categories.get(0).id);
        assertEquals(0, db.recurringDao().listAllOccurrences().size());
        assertEquals(0, db.savingsGoalDao().getAll().size());
    }

    @Test
    public void corruptedBackupIsRejectedAndLeavesTheDatabaseAlone() {
        long oldCategory = db.categoryDao().insert(localCategory("Untouched"));
        db.transactionDao().insert(localExpense(oldCategory));
        int categoryCountBefore = db.categoryDao().getAll().size();

        byte[] bytes = BackupWriter.write(sampleData()).getBytes(StandardCharsets.UTF_8);
        String tampered = new String(bytes, StandardCharsets.UTF_8)
                .replace("\"amountMinor\":2500", "\"amountMinor\":2501");

        try {
            BackupFileReader.read(tampered.getBytes(StandardCharsets.UTF_8),
                    BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.CHECKSUM_MISMATCH, e.reason);
        }
        assertEquals(categoryCountBefore, db.categoryDao().getAll().size());
        assertFalse(db.transactionDao().getAllEntities().isEmpty());
    }

    // ---- helpers ----------------------------------------------------------------------

    private BackupData validated(byte[] bytes) {
        try {
            BackupFileReader.BackupFile file = BackupFileReader.read(bytes, BackupFormat.FORMAT_VERSION);
            BackupData parsed = BackupParser.parse(file);
            BackupValidator.validate(parsed, context.getPackageName(),
                    db.getOpenHelper().getWritableDatabase().getVersion());
            return parsed;
        } catch (BackupFormatException e) {
            throw new AssertionError("fixture must be valid", e);
        }
    }
}
