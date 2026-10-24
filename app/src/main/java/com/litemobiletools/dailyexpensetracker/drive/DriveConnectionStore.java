package com.litemobiletools.dailyexpensetracker.drive;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * The only thing remembered about the Drive connection: that the user chose to connect. It is a hint for
 * whether to re-check on opening the screen, never proof of authorization. No account, no token and no
 * state is stored; the file is excluded from Android backup.
 */
public interface DriveConnectionStore {

    boolean isConnectionRequested();

    void setConnectionRequested(boolean requested);

    /** SharedPreferences-backed store holding one boolean. */
    final class Prefs implements DriveConnectionStore {
        public static final String FILE = "drive_connection";
        private static final String KEY_REQUESTED = "connection_requested";

        private final SharedPreferences prefs;

        public Prefs(@NonNull Context context) {
            this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }

        @Override
        public boolean isConnectionRequested() {
            return prefs.getBoolean(KEY_REQUESTED, false);
        }

        @Override
        public void setConnectionRequested(boolean requested) {
            prefs.edit().putBoolean(KEY_REQUESTED, requested).apply();
        }
    }
}
