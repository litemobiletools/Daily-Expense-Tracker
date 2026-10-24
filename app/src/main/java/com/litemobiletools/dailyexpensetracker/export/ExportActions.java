package com.litemobiletools.dailyexpensetracker.export;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.litemobiletools.dailyexpensetracker.R;

/**
 * Share, open and print for exported documents. Only {@code content://} URIs from the Storage Access
 * Framework are handed out, with a temporary read grant; no file paths are exposed.
 */
public final class ExportActions {

    private ExportActions() {
    }

    /** Builds the share intent (exposed for tests). */
    @NonNull
    public static Intent shareIntent(@NonNull Uri uri, @NonNull String mimeType) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeType);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    public static void share(@NonNull Activity activity, @NonNull Uri uri, @NonNull String mimeType) {
        Intent chooser = Intent.createChooser(shareIntent(uri, mimeType), activity.getString(R.string.export_share));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.export_no_app, Toast.LENGTH_LONG).show();
        }
    }

    public static void open(@NonNull Activity activity, @NonNull Uri uri, @NonNull String mimeType) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mimeType);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.export_no_app, Toast.LENGTH_LONG).show();
        }
    }

    /** Sends a saved PDF to the system print framework (which also offers "Save as PDF"). */
    public static void print(@NonNull Activity activity, @NonNull Uri uri, @NonNull String jobName) {
        PrintManager printManager = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(activity, R.string.export_no_app, Toast.LENGTH_LONG).show();
            return;
        }
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build();
        printManager.print(jobName, new UriPrintAdapter(activity.getApplicationContext(), uri, jobName), attributes);
    }
}
