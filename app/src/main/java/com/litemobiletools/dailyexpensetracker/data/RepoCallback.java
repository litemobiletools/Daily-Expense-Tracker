package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;

/** Result of an asynchronous repository call. Always invoked on the main thread. */
public interface RepoCallback<T> {

    void onSuccess(T result);

    /** {@link ValidationException} for rejected input; anything else is an unexpected database failure. */
    void onError(@NonNull Exception error);
}
