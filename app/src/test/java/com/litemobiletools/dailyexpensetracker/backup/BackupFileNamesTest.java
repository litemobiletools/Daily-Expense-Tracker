package com.litemobiletools.dailyexpensetracker.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/** The file naming rules: exact shape, uniqueness for same-second backups, stamped time. */
public class BackupFileNamesTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static long at(int y, int m, int d, int h, int min, int s) {
        Calendar c = Calendar.getInstance(GMT);
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        return c.getTimeInMillis();
    }

    @Test
    public void fileNameHasTheExactShape() {
        long now = at(2026, 10, 22, 16, 50, 0);
        String name = BackupFileNames.fileName(now, GMT);
        assertEquals("daily-expense-backup-20261022-165000.json", name);
    }

    @Test
    public void onlyTheExactShapeIsAccepted() {
        String good = "daily-expense-backup-20261022-165000.json";
        assertTrue(BackupFileNames.isBackupFileName(good));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-1650.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-2026102-165000.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-202610a2-165000.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-16500a.json"));
        assertFalse(BackupFileNames.isBackupFileName("other-backup-20261022-165000.json"));
        assertFalse(BackupFileNames.isBackupFileName("notes.json"));
        assertFalse(BackupFileNames.isBackupFileName(""));
    }

    @Test
    public void impossibleStampsAreRejected() {
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261322-165000.json"));  // month 13
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20260230-165000.json"));  // Feb 30
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261041-165000.json"));  // day 41
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-256900.json"));  // hour 25
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-167000.json"));  // minute 70
    }

    @Test
    public void duplicateSameSecondBackupsGetANumberedSuffix() {
        String candidate = BackupFileNames.fileName(at(2026, 10, 22, 16, 50, 0), GMT);
        assertEquals(candidate, BackupFileNames.unique(candidate, new HashSet<>()));
        Set<String> taken = new HashSet<>(Collections.singletonList(candidate));
        String second = BackupFileNames.unique(candidate, taken);
        assertEquals("daily-expense-backup-20261022-165000-2.json", second);
        Set<String> takenTwo = new HashSet<>();
        takenTwo.add(candidate);
        takenTwo.add(second);
        assertEquals("daily-expense-backup-20261022-165000-3.json",
                BackupFileNames.unique(candidate, takenTwo));
    }

    @Test
    public void numberedSameSecondNamesAreValidBackups() {
        assertTrue(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-2.json"));
        assertTrue(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-10.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-1.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-2"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-a2.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000--2.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-165000.json"));
        assertFalse(BackupFileNames.isBackupFileName("daily-expense-backup-20261022-165000-2-3.json"));
    }

    @Test
    public void timestampFromNameReadsTheStampInLocalTime() {
        String name = "daily-expense-backup-20261022-165000.json";
        long parsed = BackupFileNames.timestampFromName(name);
        Calendar local = Calendar.getInstance(TimeZone.getDefault());
        local.clear();
        local.set(2026, Calendar.OCTOBER, 22, 16, 50, 0);
        assertEquals(local.getTimeInMillis(), parsed);
        // Numbered same-second names carry the same stamp.
        assertEquals(local.getTimeInMillis(), BackupFileNames.timestampFromName(
                "daily-expense-backup-20261022-165000-2.json"));
    }

    @Test
    public void timestampFromNonBackupNameIsZero() {
        assertEquals(0, BackupFileNames.timestampFromName("unknown-file.json"));
        assertEquals(0, BackupFileNames.timestampFromName(
                "daily-expense-backup-20261022-165000-2-3.json"));
        assertEquals(0, BackupFileNames.timestampFromName("daily-expense-backup-165000.json"));
    }
}
