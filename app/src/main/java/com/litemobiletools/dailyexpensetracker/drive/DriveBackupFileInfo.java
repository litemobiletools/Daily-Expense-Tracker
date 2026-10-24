package com.litemobiletools.dailyexpensetracker.drive;

/** Metadata of one file in the app's hidden {@code appDataFolder}. No file content, no path. */
public final class DriveBackupFileInfo {

    public final String id;
    public final String name;
    public final long sizeBytes;
    /** Drive's last-modified time as an epoch millisecond, 0 if unknown. */
    public final long modifiedTimeMs;

    public DriveBackupFileInfo(String id, String name, long sizeBytes, long modifiedTimeMs) {
        this.id = id;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.modifiedTimeMs = modifiedTimeMs;
    }
}
