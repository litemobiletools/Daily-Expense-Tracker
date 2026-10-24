package com.litemobiletools.dailyexpensetracker.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.litemobiletools.dailyexpensetracker.backup.BackupFormatException.Reason;
import com.litemobiletools.dailyexpensetracker.data.entity.BudgetEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalContributionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringOccurrenceEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.RecurringRuleEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.SavingsGoalEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.RecurrenceFrequency;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Round trip of the versioned backup file on the JVM: collect-shaped data goes through
 * {@link BackupWriter}, {@link BackupFileReader}, {@link BackupParser} and
 * {@link BackupValidator}; all rejection cases must be typed and must reject the whole file.
 */
public class BackupFormatTest {

    private static final String APP_ID = "com.litemobiletools.dailyexpensetracker";
    private static final long NOW = 1_768_000_000_000L; // fixed non-zero instant
    private static final long T = 1_760_000_000_000L;   // record time

    private BackupData data;

    @Before
    public void makeData() {
        CategoryEntity c = new CategoryEntity();
        c.id = 3;
        c.name = "Groceries";
        c.type = TransactionType.EXPENSE;
        c.iconKey = "basket";
        c.colorArgb = 0xFF336699; // a real app color: alpha 0xFF, so the int is NEGATIVE
        c.isActive = true;
        c.defaultKey = "groceries";
        c.sortOrder = 1;
        c.createdAt = T;
        c.updatedAt = T;

        PaymentMethodEntity p = new PaymentMethodEntity();
        p.id = 2;
        p.name = "Cash";
        p.iconKey = "cash";
        p.isActive = true;
        p.defaultKey = "cash";
        p.sortOrder = 0;

        TransactionEntity t = new TransactionEntity();
        t.id = 7;
        t.type = TransactionType.EXPENSE;
        t.amountMinor = 1250; // 12.50 USD
        t.currencyCode = "USD";
        t.categoryId = c.id;
        t.paymentMethodId = p.id;
        t.transactionTimestamp = T;
        t.note = "Bulk buy";
        t.createdAt = T;
        t.updatedAt = T;
        assertEquals("Bulk buy", t.note);

        BudgetEntity b = new BudgetEntity();
        b.id = 4;
        b.period = BudgetPeriod.MONTHLY;
        b.categoryId = null; // total budget, the unique key has null category
        b.currencyCode = "USD";
        b.amountMinor = 50000;
        b.createdAt = T;
        b.updatedAt = T;

        SavingsGoalEntity g = new SavingsGoalEntity();
        g.id = 5;
        g.name = "Holiday";
        g.targetMinor = 100000;
        g.currencyCode = "USD";
        g.targetDate = null;
        g.isCompleted = false;
        g.completedAt = null;
        g.createdAt = T;
        g.updatedAt = T;

        GoalContributionEntity gc = new GoalContributionEntity();
        gc.id = 6;
        gc.goalId = g.id;
        gc.deltaMinor = 1500;
        gc.note = null;
        gc.contributedAt = T;

        RecurringRuleEntity r = new RecurringRuleEntity();
        r.id = 8;
        r.type = TransactionType.EXPENSE;
        r.amountMinor = 999;
        r.currencyCode = "USD";
        r.categoryId = c.id;
        r.paymentMethodId = null;
        r.note = null;
        r.frequency = RecurrenceFrequency.MONTHLY;
        r.startDate = T;
        r.endDate = null;
        r.nextIndex = 2;
        r.isActive = true;
        r.skippedCount = 1;
        r.createdAt = T;
        r.updatedAt = T;

        RecurringOccurrenceEntity o = new RecurringOccurrenceEntity();
        o.id = 9;
        o.ruleId = r.id;
        o.occurrenceDate = T;
        o.transactionId = t.id;
        o.createdAt = T;

        data = new BackupData(new BackupData.AppInfo(APP_ID, "1.2.3", 7, 1), NOW,
                new BackupData.Preferences(2, "CATCH_UP"),
                new java.util.ArrayList<>(java.util.Collections.singletonList(c)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(p)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(t)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(b)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(g)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(gc)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(r)),
                new java.util.ArrayList<>(java.util.Collections.singletonList(o)));
    }

    private byte[] fileBytes() {
        return BackupWriter.write(data).getBytes(StandardCharsets.UTF_8);
    }

    private BackupData roundTrip(byte[] bytes) throws BackupFormatException {
        BackupFileReader.BackupFile file = BackupFileReader.read(bytes, BackupFormat.FORMAT_VERSION);
        BackupData parsed = BackupParser.parse(file);
        BackupValidator.validate(parsed, APP_ID, 2);
        return parsed;
    }

    @Test
    public void roundTripKeepsEveryRecordAndPreference() throws BackupFormatException {
        BackupData back = roundTrip(fileBytes());
        assertEquals(8, back.recordCount());
        assertEquals(NOW, back.createdAtMs);
        assertEquals("1.2.3", back.appInfo.appVersion);
        assertEquals(7, back.appInfo.appVersionCode);
        assertEquals(1, back.appInfo.dbSchemaVersion);
        assertEquals(2, back.preferences.themeMode);
        assertEquals("CATCH_UP", back.preferences.missedPolicy);

        TransactionEntity t = back.transactions.get(0);
        assertEquals(7, t.id);
        assertEquals(TransactionType.EXPENSE, t.type);
        assertEquals(1250, t.amountMinor); // money stays long minor units, never float
        assertEquals("Bulk buy", t.note);
        assertEquals(3, t.categoryId);
        assertEquals(Long.valueOf(2), t.paymentMethodId);

        CategoryEntity c = back.categories.get(0);
        assertEquals(Integer.valueOf(0xFF336699), c.colorArgb);
        assertEquals("groceries", c.defaultKey);

        RecurringOccurrenceEntity o = back.recurringOccurrences.get(0);
        assertEquals(8, o.ruleId);
        assertEquals(Long.valueOf(7), o.transactionId);

        BudgetEntity b = back.budgets.get(0);
        assertEquals(null, b.categoryId);
        assertEquals(50000, b.amountMinor);

        GoalContributionEntity gc = back.goalContributions.get(0);
        assertEquals(1500, gc.deltaMinor);
        assertEquals(5, gc.goalId);
    }

    @Test
    public void negativeDeviceColorRoundTripsBitExact() throws BackupFormatException {
        // The exact bug that rejected real device backups: the fixture category color has alpha
        // 0xFF, so the CategoryEntity int is negative. The file must carry the unsigned
        // 0..0xFFFFFFFF form, and the reader must hand back the identical (negative) int.
        data.categories.get(0).colorArgb = 0xFF3E7CB1; // DefaultDataSeeder's "restaurant" literal

        String file = BackupWriter.write(data);
        // The unsigned form as a JSON number; never the sign-extended negative number.
        org.junit.Assert.assertTrue(
                file.contains("\"colorArgb\":" + (0xFF3E7CB1L & 0xFFFFFFFFL)));
        org.junit.Assert.assertFalse(file.contains("\"colorArgb\":-"));

        BackupData back = roundTrip(fileBytes());
        assertEquals(0xFF3E7CB1, back.categories.get(0).colorArgb.longValue());
        assertEquals(-12682063, back.categories.get(0).colorArgb.longValue()); // same value, signed view
    }

    @Test
    public void legacySignExtendedColorFromOlderBuildIsAccepted() throws BackupFormatException {
        // Backups uploaded by the build that wrote colors sign-extended must still restore.
        String legacyData = BackupWriter.dataJson(data).replace(
                "\"colorArgb\":" + (0xFF336699L & 0xFFFFFFFFL),
                "\"colorArgb\":-13408615"); // 0xFF336699 as a signed int, the old writer's output
        String header = BackupWriter.headerLine(data,
                BackupIntegrity.sha256Hex(legacyData.getBytes(StandardCharsets.UTF_8)));
        byte[] bytes = (header + (char) BackupFormat.HEADER_SEPARATOR + legacyData)
                .getBytes(StandardCharsets.UTF_8);
        BackupData back = roundTrip(bytes);
        assertEquals(Integer.valueOf(0xFF336699), back.categories.get(0).colorArgb);
    }

    @Test
    public void outputIsDeterministicAndHasTheDeclareStructure() {
        String first = BackupWriter.write(data);
        assertEquals(first, BackupWriter.write(data));
        int separator = first.indexOf('\n');
        assertTrue(separator > 0);
        String header = first.substring(0, separator);
        assertTrue(header.startsWith("{\"format\":\"det-backup\",\"formatVersion\":1,\"createdAtMs\":" + NOW
                + ",\"metadata\":{\"appId\":\"" + APP_ID + "\""));
        assertTrue(header.contains("\"recordCounts\":{\"categories\":1,\"paymentMethods\":1,"
                + "\"transactions\":1,\"budgets\":1,\"savingsGoals\":1,\"goalContributions\":1,"
                + "\"recurringRules\":1,\"recurringOccurrences\":1,\"preferences\":1}"));
        assertTrue(header.endsWith("\"integrity\":{\"algorithm\":\"SHA-256\",\"dataSha256\":\""
                + com.litemobiletools.dailyexpensetracker.backup.BackupIntegrity.sha256Hex(
                        first.substring(separator + 1).getBytes(StandardCharsets.UTF_8))
                + "\"}}"));
        // Exactly one header line: the separator newline appears once in the whole file.
        assertEquals(first.indexOf('\n'), first.lastIndexOf('\n'));
    }

    @Test
    public void emptyFileIsRejected() {
        try {
            BackupFileReader.read(new byte[0], BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.EMPTY_FILE, e.reason);
        }
    }

    @Test
    public void noHeaderSeparatorIsRejected() {
        byte[] bytes = "no newline here".getBytes(StandardCharsets.UTF_8);
        try {
            BackupFileReader.read(bytes, BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.MALFORMED_HEADER, e.reason);
        }
    }

    @Test
    public void notJsonHeaderRejected() {
        String text = "not json\n{\"categories\":[]}";
        try {
            BackupFileReader.read(text.getBytes(StandardCharsets.UTF_8), BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.MALFORMED_HEADER, e.reason);
        }
    }

    @Test
    public void unknownFormatMarkerRejected() {
        String text = BackupWriter.write(data);
        String tampered = text.replace("\"format\":\"det-backup\"", "\"format\":\"other\"");
        try {
            roundTrip(tampered.getBytes(StandardCharsets.UTF_8));
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.UNSUPPORTED_FORMAT, e.reason);
        }
    }

    private byte[] tamperDataKeepDigest(String updateFrom, String updateTo) {
        // The header digest only covers the exact data bytes, so a data edit is re-signed to reach
        // the deeper validation instead of stopping at the checksum.
        String full = BackupWriter.write(data);
        String dataPart = full.substring(full.indexOf('\n') + 1);
        String tampered = dataPart.replace(updateFrom, updateTo);
        assertNotEquals(dataPart, tampered);
        String file = BackupWriter.headerLine(data,
                com.litemobiletools.dailyexpensetracker.backup.BackupIntegrity
                        .sha256Hex(tampered.getBytes(StandardCharsets.UTF_8)))
                + '\n' + tampered;
        return file.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void newerFormatVersionRejectedWithoutTouchingLocalData() {
        String text = BackupWriter.write(data);
        String tampered = text.replace("\"formatVersion\":1", "\"formatVersion\":2");
        try {
            BackupFileReader.read(tampered.getBytes(StandardCharsets.UTF_8), BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.NEWER_VERSION, e.reason);
        }
    }

    @Test
    public void corruptedDataBytesFailTheChecksum() {
        String text = BackupWriter.write(data);
        // Flip a value inside the data part; the header digest no longer matches.
        String tampered = text.replace("\"amountMinor\":1250", "\"amountMinor\":1251");
        assertNotEquals(text, tampered);
        try {
            BackupFileReader.read(tampered.getBytes(StandardCharsets.UTF_8), BackupFormat.FORMAT_VERSION);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.CHECKSUM_MISMATCH, e.reason);
        }
    }

    @Test
    public void headerCountsMustMatchTheData() {
        String text = BackupWriter.write(data);
        String tampered = text.replace("\"transactions\":1,", "\"transactions\":2,");
        try {
            roundTrip(tampered.getBytes(StandardCharsets.UTF_8));
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.COUNT_MISMATCH, e.reason);
            assertTrue(e.getMessage().contains("transactions"));
        }
    }

    @Test
    public void anotherAppsBackupIsRejectedByTheValidator() throws BackupFormatException {
        BackupFileReader.BackupFile file =
                BackupFileReader.read(fileBytes(), BackupFormat.FORMAT_VERSION);
        BackupData parsed = BackupParser.parse(file);
        try {
            BackupValidator.validate(parsed, "com.example.other", 1);
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.WRONG_APP_ID, e.reason);
        }
    }

    @Test
    public void invalidValuesAreRejectedByTheValidator() throws BackupFormatException {
        data.transactions.get(0).amountMinor = 0; // not positive
        try {
            roundTrip(fileBytes());
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.INVALID_RECORD, e.reason);
        }
    }

    @Test
    public void brokenReferencesAreRejectedByTheValidator() throws BackupFormatException {
        data.goalContributions.get(0).goalId = 999; // no such goal in the backup
        try {
            roundTrip(fileBytes());
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.BROKEN_REFERENCE, e.reason);
        }
    }

    @Test
    public void duplicateIdsAreRejectedByTheValidator() throws BackupFormatException {
        data.transactions.get(0).categoryId = 3;
        CategoryEntity fake = new CategoryEntity();
        fake.id = 3; // duplicate category id
        fake.name = "Groceries copy";
        fake.type = TransactionType.EXPENSE;
        fake.iconKey = "x";
        fake.isActive = false;
        fake.defaultKey = null;
        fake.sortOrder = 0;
        fake.createdAt = T;
        fake.updatedAt = T;
        data.categories.add(fake);
        try {
            roundTrip(fileBytes());
            fail();
        } catch (BackupFormatException e) {
            assertEquals(Reason.INVALID_RECORD, e.reason);
        }
    }

    @Test
    public void noRecordIsEverSilentlySkippedInTheParser() throws BackupFormatException {
        // Corrupt one budget row's enum value: the parse must reject the whole file, not skip the row.
        byte[] badPeriod = tamperDataKeepDigest("\"period\":\"MONTHLY\"", "\"period\":\"HOLIDAY\"");
        try {
            roundTrip(badPeriod);
            fail();
        } catch (BackupFormatException e) {
            assertTrue(e.reason == Reason.MALFORMED_RECORD || e.reason == Reason.INVALID_RECORD);
        }
    }

    @Test
    public void preferencesMustBeKnownValues() {
        BackupFormatException e = assertRoundTripRejected(tamperDataKeepDigest(
                "\"themeMode\":2", "\"themeMode\":5"));
        assertEquals(Reason.INVALID_RECORD, e.reason);
    }

    @Test
    public void unknownMissedPolicyRejected() {
        BackupFormatException e = assertRoundTripRejected(tamperDataKeepDigest(
                "\"missedPolicy\":\"CATCH_UP\"", "\"missedPolicy\":\"GUESS\""));
        assertEquals(Reason.INVALID_RECORD, e.reason);
    }

    private BackupFormatException assertRoundTripRejected(byte[] bytes) {
        try {
            roundTrip(bytes);
            fail();
            throw new AssertionError(); // unreachable, keeps the compiler happy about the return
        } catch (BackupFormatException expected) {
            return expected;
        }
    }

    @Test
    public void recordCountAddsEveryTableButNotPreferences() {
        assertEquals(8, data.recordCount());
        BackupData empty = new BackupData(data.appInfo, NOW, data.preferences,
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
        assertEquals(0, empty.recordCount());
    }

    @Test
    public void displayTimePrefersTheFileNamesStamp() {
        String name = BackupFileNames.fileName(NOW, java.util.TimeZone.getTimeZone("GMT"));
        assertEquals(BackupFileNames.timestampFromName(name),
                data.displayTimeMs(name));
        assertNotNull(name);
        assertFalse(BackupFileNames.PREFIX.isEmpty());
    }
}
