package com.litemobiletools.dailyexpensetracker.export;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.ReportRepository;
import com.litemobiletools.dailyexpensetracker.model.ReportData;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes an export to a document the user picked with the Storage Access Framework, on a background
 * thread of its own (the database executor stays free for the UI). Nothing is sent anywhere, file
 * contents and amounts are never logged, and if writing fails the partially written document is deleted.
 * Success is reported only after the stream has been fully written and closed.
 */
public final class ExportRunner {

    private static final String TAG = "ExportRunner";
    private static final int PDF_PAGE_QUERY = 500;

    /** A finished export. */
    public static final class Result {
        @NonNull public final Uri uri;
        @NonNull public final String mimeType;
        /** Transactions, category rows, budget rows, or PDF pages, depending on the kind. */
        public final long count;
        @NonNull public final ExportRequest.Kind kind;

        Result(@NonNull Uri uri, @NonNull String mimeType, long count, @NonNull ExportRequest.Kind kind) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.count = count;
            this.kind = kind;
        }
    }

    public interface Callback {
        void onSuccess(@NonNull Result result);

        void onError(@NonNull Exception error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "export-worker");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ExportRunner() {
    }

    public static void run(@NonNull Context context, @NonNull ExportRequest request, @NonNull Uri uri,
                           @NonNull Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ContentResolver resolver = app.getContentResolver();
            long count;
            try {
                OutputStream out = open(resolver, uri);
                try {
                    count = writeSync(app, AppDatabase.getInstance(app), request, out,
                            System.currentTimeMillis(), TimeZone.getDefault(), Locale.getDefault());
                    out.flush();
                } finally {
                    out.close();
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "Export failed: " + e.getClass().getSimpleName());
                deleteQuietly(resolver, uri);
                final Exception error = e instanceof Exception ? (Exception) e : new IOException("Out of memory");
                MAIN.post(() -> callback.onError(error));
                return;
            }
            final Result result = new Result(uri, request.mimeType(), count, request.kind);
            MAIN.post(() -> callback.onSuccess(result));
        });
    }

    /** Writes the export to {@code out}; returns the number of records (or pages for a PDF). */
    @WorkerThread
    public static long writeSync(@NonNull Context context, @NonNull AppDatabase db, @NonNull ExportRequest request,
                                 @NonNull OutputStream out, long now, @NonNull TimeZone zone,
                                 @NonNull Locale locale) throws IOException {
        switch (request.kind) {
            case TRANSACTIONS_CSV:
                return CsvExporter.writeTransactions(db, out, request.type,
                        request.allTime ? null : request.range.start,
                        request.allTime ? null : request.range.endExclusive, zone);
            case CATEGORY_CSV:
                return CsvExporter.writeCategorySummary(report(db, request, now, zone), out, zone);
            case BUDGET_CSV:
                return CsvExporter.writeBudgetSummary(report(db, request, now, zone), out, zone);
            default: {
                ReportData data = report(db, request, now, zone);
                List<TransactionRow> rows = request.includeTransactions ? reportTransactions(db, request) : null;
                return PdfReportWriter.write(out, data, rows, new PdfReportWriter.Labels(context), zone, locale, now);
            }
        }
    }

    private static ReportData report(AppDatabase db, ExportRequest request, long now, TimeZone zone) {
        return new ReportRepository(db).loadSync(request.currencyCode, request.range, now, zone,
                BudgetRepository.firstDayOfWeek());
    }

    /** The report's own transactions (its currency and range), oldest first, read with keyset paging. */
    private static List<TransactionRow> reportTransactions(AppDatabase db, ExportRequest request) {
        List<TransactionRow> rows = new ArrayList<>();
        long afterTimestamp = Long.MIN_VALUE;
        long afterId = Long.MIN_VALUE;
        while (true) {
            List<TransactionRow> page = db.transactionDao().exportPage(null, request.currencyCode,
                    request.range.start, request.range.endExclusive, afterTimestamp, afterId, PDF_PAGE_QUERY);
            rows.addAll(page);
            if (page.size() < PDF_PAGE_QUERY) {
                return rows;
            }
            TransactionRow last = page.get(page.size() - 1);
            afterTimestamp = last.transactionTimestamp;
            afterId = last.id;
        }
    }

    private static OutputStream open(ContentResolver resolver, Uri uri) throws IOException {
        OutputStream out;
        try {
            out = resolver.openOutputStream(uri, "wt"); // truncate: never leave old bytes behind
        } catch (FileNotFoundException | IllegalArgumentException e) {
            out = resolver.openOutputStream(uri, "w");
        }
        if (out == null) {
            throw new IOException("Could not open the chosen file");
        }
        return out;
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
            // The provider may not support deletion; the partial file is then left for the user to remove.
        }
    }
}
