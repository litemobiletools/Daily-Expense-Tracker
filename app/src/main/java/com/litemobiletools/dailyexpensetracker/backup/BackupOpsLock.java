package com.litemobiletools.dailyexpensetracker.backup;

import java.util.concurrent.Semaphore;

/**
 * Process-wide coordination for backup and restore operations: one permit, shared by the manual
 * operations (BackupViewModel) and the automatic backup worker (AutoBackupWorker). Acquisition is
 * always immediate and non-blocking — a caller that cannot get the permit reports a busy state
 * instead of waiting, so no path ever holds the lock indefinitely. The permit is released by each
 * operation in its own finally block. The lock lives only inside this process; the restore and
 * backup steps themselves never rely on it for data safety (the database work stays transactional
 * and the safety snapshot stays in place).
 */
public final class BackupOpsLock {

    private static final Semaphore PERMIT = new Semaphore(1, true);

    private BackupOpsLock() {
    }

    /** True if this caller now holds the single operation permit. Non-blocking. */
    public static boolean tryAcquire() {
        return PERMIT.tryAcquire();
    }

    /** Gives the permit back; the caller must hold it. Harmless if called without holding it. */
    public static void release() {
        PERMIT.release();
    }

}
