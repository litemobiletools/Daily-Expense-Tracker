package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The verified header of a backup file, parsed into plain types. */
public final class BackupHeadMeta {

    public final long createdAtMs;
    public final int formatVersion;
    @NonNull public final String appId;
    @NonNull public final String appVersion;
    public final int appVersionCode;
    /** Room schema version the backup was written with (1 or 2 today). */
    public final int dbSchemaVersion;
    /** Declared record counts per data section (verbatim from the header). */
    @NonNull public final Map<String, Integer> recordCounts;
    /** Recorded SHA-256 over the data bytes that follow the header. */
    @NonNull public final String dataSha256Hex;

    BackupHeadMeta(long createdAtMs, int formatVersion, @NonNull String appId,
                   @NonNull String appVersion, int appVersionCode, int dbSchemaVersion,
                   @NonNull Map<String, Integer> recordCounts, @NonNull String dataSha256Hex) {
        this.createdAtMs = createdAtMs;
        this.formatVersion = formatVersion;
        this.appId = appId;
        this.appVersion = appVersion;
        this.appVersionCode = appVersionCode;
        this.dbSchemaVersion = dbSchemaVersion;
        this.recordCounts = Collections.unmodifiableMap(new HashMap<>(recordCounts));
        this.dataSha256Hex = dataSha256Hex;
    }

    /** Declared count of one data section, or -1 when the section was not declared at all. */
    public int declaredCount(@NonNull String tableKey) {
        Integer count = recordCounts.get(tableKey);
        return count == null ? -1 : count;
    }
}
