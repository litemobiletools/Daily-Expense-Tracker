package com.litemobiletools.dailyexpensetracker.backup;

import androidx.annotation.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** The integrity mechanism of the backup file: a hex SHA-256 over the exact bytes it covers. */
public final class BackupIntegrity {

    private BackupIntegrity() {
    }

    /** Hex SHA-256 of the given bytes, 64 lowercase characters. */
    @NonNull
    public static String sha256Hex(@NonNull byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(BackupFormat.HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is guaranteed on every AndroidDalvik JVM");
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    /** True for a well-formed 64-character lowercase/uppercase hex digest. */
    public static boolean isSha256Hex(@NonNull String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
