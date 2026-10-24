package com.litemobiletools.dailyexpensetracker.export;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import androidx.annotation.NonNull;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Prints an already generated PDF by copying it from its content URI on a background thread. */
final class UriPrintAdapter extends PrintDocumentAdapter {

    private final Context context;
    private final Uri uri;
    private final String name;
    private final Handler main = new Handler(Looper.getMainLooper());

    UriPrintAdapter(@NonNull Context context, @NonNull Uri uri, @NonNull String name) {
        this.context = context;
        this.uri = uri;
        this.name = name;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();
        callback.onLayoutFinished(info, false);
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        new Thread(() -> {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                if (in == null) {
                    throw new IOException("Document not available");
                }
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancellationSignal.isCanceled()) {
                        main.post(callback::onWriteCancelled);
                        return;
                    }
                    out.write(buffer, 0, read);
                }
                main.post(() -> callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES}));
            } catch (Exception e) {
                main.post(() -> callback.onWriteFailed(e.getClass().getSimpleName()));
            }
        }, "print-copy").start();
    }
}
