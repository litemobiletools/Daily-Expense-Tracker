package com.litemobiletools.dailyexpensetracker.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs all database work on one background thread and delivers results on the main thread.
 * Failures (corrupt file, disk full, validation) are caught and reported to the callback's
 * {@link RepoCallback#onError} instead of crashing the app.
 */
public final class DatabaseExecutor {

    private static final String TAG = "DatabaseExecutor";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "database-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DatabaseExecutor() {
    }

    public static void execute(@NonNull Runnable task) {
        EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Background database task failed", e);
            }
        });
    }

    /** Runs {@code work} off the main thread and posts its result or failure to the main thread. */
    public static <T> void run(@NonNull Callable<T> work, @Nullable RepoCallback<T> callback) {
        EXECUTOR.execute(() -> {
            T result = null;
            Exception failure = null;
            try {
                result = work.call();
            } catch (Exception e) {
                failure = e;
                if (!(e instanceof ValidationException)) {
                    Log.e(TAG, "Database operation failed", e);
                }
            }
            if (callback == null) {
                return;
            }
            final T finalResult = result;
            final Exception finalFailure = failure;
            MAIN.post(() -> {
                if (finalFailure != null) {
                    callback.onError(finalFailure);
                } else {
                    callback.onSuccess(finalResult);
                }
            });
        });
    }
}
