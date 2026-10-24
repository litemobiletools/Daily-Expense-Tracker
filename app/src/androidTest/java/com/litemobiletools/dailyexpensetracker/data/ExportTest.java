package com.litemobiletools.dailyexpensetracker.data;

import static com.litemobiletools.dailyexpensetracker.data.TestSupport.USD;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.UTC;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.at;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.categoryId;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.expense;
import static com.litemobiletools.dailyexpensetracker.data.TestSupport.income;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.litemobiletools.dailyexpensetracker.data.entity.TransactionEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.BudgetRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.ReportRepository;
import com.litemobiletools.dailyexpensetracker.export.CsvExporter;
import com.litemobiletools.dailyexpensetracker.export.ExportActions;
import com.litemobiletools.dailyexpensetracker.export.ExportRequest;
import com.litemobiletools.dailyexpensetracker.export.ExportRunner;
import com.litemobiletools.dailyexpensetracker.export.PdfReportWriter;
import com.litemobiletools.dailyexpensetracker.model.BudgetPeriod;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;
import com.litemobiletools.dailyexpensetracker.util.ReportPeriods;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** CSV and PDF exports checked against the database. Made-up data only. */
@RunWith(AndroidJUnit4.class)
public class ExportTest {

    private Context context;
    private AppDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        db = TestSupport.newDatabase();
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---- tiny RFC 4180 parser for verification ----------------------------------------

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                field.append(c);
            }
            i++;
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private String exportTransactions(TransactionType type, Long start, Long end) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvExporter.writeTransactions(db, out, type, start, end, UTC);
        byte[] bytes = out.toByteArray();
        // UTF-8 byte-order mark first.
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    private long addNote(TransactionType type, long minor, String category, String note, long when) throws Exception {
        return new com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository(db).insertSync(
                new TransactionInput(type, minor, USD, categoryId(db, type, category), null, when, note));
    }

    // ---- CSV -------------------------------------------------------------------------

    @Test
    public void transactionCsvHasHeadersCrlfAndEveryRecord() throws Exception {
        income(db, 250000, "Salary", at(2026, 10, 1, 9, 0));
        expense(db, 1250, "Food", at(2026, 10, 2, 13, 45));
        String csv = exportTransactions(null, null, null);
        assertTrue(csv.startsWith(String.join(",", CsvExporter.TRANSACTION_HEADER) + "\r\n"));
        List<List<String>> rows = parseCsv(csv);
        assertEquals(3, rows.size());
        List<String> food = rows.get(2);                       // oldest first
        assertEquals("2026-10-02", food.get(1));
        assertEquals("13:45", food.get(2));
        assertEquals("2026-10-02T13:45:00Z", food.get(3));
        assertEquals("EXPENSE", food.get(4));
        assertEquals("12.50", food.get(5));
        assertEquals("-12.50", food.get(6));
        assertEquals("USD", food.get(7));
        assertEquals("Food", food.get(8));
        assertEquals("2500.00", rows.get(1).get(5));
    }

    @Test
    public void csvEscapesCommasQuotesLineBreaksAndNeutralisesFormulas() throws Exception {
        addNote(TransactionType.EXPENSE, 100, "Food", "Lunch, with \"Sam\"\nand team", at(2026, 10, 2, 9, 0));
        addNote(TransactionType.EXPENSE, 200, "Food", "=HYPERLINK(\"http://example.com\")", at(2026, 10, 3, 9, 0));
        addNote(TransactionType.EXPENSE, 300, "Food", "@SUM(1+1)", at(2026, 10, 4, 9, 0));
        addNote(TransactionType.EXPENSE, 400, "Food", "Café ☕ ৳ 日本", at(2026, 10, 5, 9, 0));
        String csv = exportTransactions(null, null, null);
        List<List<String>> rows = parseCsv(csv);
        assertEquals(5, rows.size());
        for (List<String> row : rows) {
            assertEquals(CsvExporter.TRANSACTION_HEADER.length, row.size());
        }
        assertEquals("Lunch, with \"Sam\"\nand team", rows.get(1).get(10));
        assertEquals("'=HYPERLINK(\"http://example.com\")", rows.get(2).get(10));
        assertEquals("'@SUM(1+1)", rows.get(3).get(10));
        assertEquals("Café ☕ ৳ 日本", rows.get(4).get(10));            // UTF-8 round trip
    }

    @Test
    public void csvFiltersByTypeAndFullDayRange() throws Exception {
        income(db, 5000, "Salary", at(2026, 10, 1, 9, 0));
        expense(db, 1, "Food", at(2026, 9, 30, 23, 59));
        expense(db, 2, "Food", at(2026, 10, 1, 0, 0));
        expense(db, 3, "Food", at(2026, 10, 31, 23, 59));
        expense(db, 4, "Food", at(2026, 11, 1, 0, 0));
        ReportRange october = ReportPeriods.resolve(ReportPreset.THIS_MONTH, at(2026, 10, 15, 0, 0), UTC, Calendar.MONDAY);

        List<List<String>> expenses = parseCsv(exportTransactions(TransactionType.EXPENSE, october.start, october.endExclusive));
        assertEquals(3, expenses.size());
        assertEquals("0.02", expenses.get(1).get(5));
        assertEquals("0.03", expenses.get(2).get(5));
        List<List<String>> incomeOnly = parseCsv(exportTransactions(TransactionType.INCOME, null, null));
        assertEquals(2, incomeOnly.size());
        assertEquals("INCOME", incomeOnly.get(1).get(4));
    }

    @Test
    public void exportedAmountsAddUpToTheDatabaseTotals() throws Exception {
        for (int i = 0; i < 40; i++) {
            expense(db, 137 + i * 11L, i % 2 == 0 ? "Food" : "Bills", at(2026, 10, 1, 0, 0) + i * 3600_000L);
        }
        income(db, 99999, "Salary", at(2026, 10, 2, 9, 0));
        List<List<String>> rows = parseCsv(exportTransactions(null, null, null));
        BigDecimal net = BigDecimal.ZERO;
        for (List<String> row : rows.subList(1, rows.size())) {
            net = net.add(new BigDecimal(row.get(6)));
        }
        long dbNet = db.transactionDao().netBalance(USD, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(BigDecimal.valueOf(dbNet, 2), net);
    }

    @Test
    public void largeExportsAreCompleteWithoutDuplicatesAndRunOffTheMainThread() throws Exception {
        final int total = 5000;
        long food = categoryId(db, TransactionType.EXPENSE, "Food");
        db.runInTransaction(() -> {
            long base = at(2024, 1, 1, 0, 0);
            for (int i = 0; i < total; i++) {
                TransactionEntity t = new TransactionEntity();
                t.type = TransactionType.EXPENSE;
                t.amountMinor = 100 + i;
                t.currencyCode = USD;
                t.categoryId = food;
                // Many identical timestamps to exercise the (timestamp, id) keyset.
                t.transactionTimestamp = base + (i / 7) * 60_000L;
                t.note = "row " + i;
                t.createdAt = base;
                t.updatedAt = base;
                db.transactionDao().insert(t);
            }
            return null;
        });
        assertFalse(Looper.getMainLooper().isCurrentThread());
        long t0 = System.nanoTime();
        String csv = exportTransactions(null, null, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        List<List<String>> rows = parseCsv(csv);
        assertEquals(total + 1, rows.size());
        Set<String> ids = new HashSet<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            assertTrue("duplicate " + row.get(0), ids.add(row.get(0)));
        }
        assertEquals(db.transactionDao().exportCount(null, null, null, null), ids.size());
        assertTrue("export took " + ms + " ms", ms < 20000);
    }

    @Test
    public void categoryAndBudgetSummariesMatchTheReport() throws Exception {
        new BudgetRepository(db).saveSync(null, BudgetPeriod.MONTHLY, null, USD, 10000);
        expense(db, 3000, "Food", at(2026, 10, 2, 9, 0));
        expense(db, 1000, "Food", at(2026, 10, 3, 9, 0));
        expense(db, 6000, "Bills", at(2026, 10, 4, 9, 0));
        income(db, 20000, "Salary", at(2026, 10, 1, 9, 0));
        long now = at(2026, 10, 22, 12, 0);
        ReportRange range = ReportPeriods.resolve(ReportPreset.THIS_MONTH, now, UTC, Calendar.MONDAY);
        ReportData data = new ReportRepository(db).loadSync(USD, range, now, UTC, Calendar.MONDAY);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int written = CsvExporter.writeCategorySummary(data, out, UTC);
        List<List<String>> rows = parseCsv(new String(out.toByteArray(), StandardCharsets.UTF_8).substring(1));
        assertEquals(written + 1, rows.size());
        assertEquals(String.join(",", CsvExporter.CATEGORY_HEADER), String.join(",", rows.get(0)));
        assertEquals("2026-10-01", rows.get(1).get(0));
        assertEquals("2026-10-31", rows.get(1).get(1));
        assertEquals("Bills", rows.get(1).get(4));
        assertEquals("60.00", rows.get(1).get(6));
        assertEquals("60.0", rows.get(1).get(7));
        assertEquals("Food", rows.get(2).get(4));
        assertEquals("2", rows.get(2).get(5));
        assertEquals(CsvExporter.ALL_CATEGORIES, rows.get(3).get(4));
        assertEquals("100.00", rows.get(3).get(6));
        assertEquals(CurrencyFormatter.plainAmount(data.expenseMinor, USD), rows.get(3).get(6));
        assertEquals("INCOME", rows.get(4).get(3));

        ByteArrayOutputStream budgetOut = new ByteArrayOutputStream();
        assertEquals(1, CsvExporter.writeBudgetSummary(data, budgetOut, UTC));
        List<List<String>> budgetRows = parseCsv(new String(budgetOut.toByteArray(), StandardCharsets.UTF_8).substring(1));
        assertEquals("MONTHLY", budgetRows.get(1).get(0));
        assertEquals("100.00", budgetRows.get(1).get(5));
        assertEquals("100.00", budgetRows.get(1).get(6));
        assertEquals("0.00", budgetRows.get(1).get(7));
        assertEquals("REACHED", budgetRows.get(1).get(9));
    }

    @Test
    public void exportRunnerWritesEveryKindAndReportsCounts() throws Exception {
        expense(db, 1500, "Food", at(2026, 10, 2, 9, 0));
        long now = at(2026, 10, 22, 12, 0);
        ReportRange range = ReportPeriods.resolve(ReportPreset.THIS_MONTH, now, UTC, Calendar.MONDAY);
        for (ExportRequest.Kind kind : ExportRequest.Kind.values()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long count = ExportRunner.writeSync(context, db, new ExportRequest(kind, range, USD, false, null, true),
                    out, now, UTC, Locale.US);
            assertTrue(kind + " wrote nothing", out.size() > 0);
            if (kind == ExportRequest.Kind.TRANSACTIONS_CSV) {
                assertEquals(1, count);
            }
            if (kind == ExportRequest.Kind.PDF) {
                assertEquals(1, count);
                assertEquals("%PDF", new String(out.toByteArray(), 0, 4, StandardCharsets.US_ASCII));
            }
        }
    }

    // ---- PDF -------------------------------------------------------------------------

    /** Records every draw call so layout rules can be checked. */
    private static final class RecordingSink implements PdfReportWriter.Sink {
        final List<List<String>> pageTexts = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int pages;

        @Override
        public void beginPage(int pageNumber) {
            pages++;
            pageTexts.add(new ArrayList<>());
        }

        @Override
        public void text(@NonNull String text, float x, float baseline, @NonNull Paint paint) {
            pageTexts.get(pages - 1).add(text);
            float width = paint.measureText(text);
            if (x < PdfReportWriter.MARGIN - 0.5f || x + width > PdfReportWriter.PAGE_WIDTH - PdfReportWriter.MARGIN + 0.5f) {
                problems.add("horizontal overflow on page " + pages + ": " + text);
            }
            if (baseline < PdfReportWriter.MARGIN || baseline > PdfReportWriter.PAGE_HEIGHT - PdfReportWriter.MARGIN + 0.5f) {
                problems.add("vertical overflow on page " + pages + ": " + text);
            }
        }

        @Override public void line(float x1, float y1, float x2, float y2, @NonNull Paint paint) { }
        @Override public void rect(float l, float t, float r, float b, @NonNull Paint paint) { }
        @Override public void endPage() { }
    }

    private ReportData octoberReport(long now) {
        ReportRange range = ReportPeriods.resolve(ReportPreset.THIS_MONTH, now, UTC, Calendar.MONDAY);
        return new ReportRepository(db).loadSync(USD, range, now, UTC, Calendar.MONDAY);
    }

    private List<TransactionRow> octoberRows(long now) {
        ReportRange range = ReportPeriods.resolve(ReportPreset.THIS_MONTH, now, UTC, Calendar.MONDAY);
        return db.transactionDao().exportPage(null, USD, range.start, range.endExclusive, Long.MIN_VALUE, Long.MIN_VALUE, 100000);
    }

    @Test
    public void pdfPaginatesRepeatsHeadingsWrapsLongTextAndNumbersPages() throws Exception {
        com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository categories =
                new com.litemobiletools.dailyexpensetracker.data.repository.CategoryRepository(db);
        long longCat = categories.addSync("Extremely long category name x", TransactionType.EXPENSE, "x", null);
        // 93 characters (the limit is 100), including a 64-character word that must be broken.
        String longNote = "Averyveryverylongwordwithoutanyspacesthatmustbebrokenacrosslines"
                + " and more words to wrap too";
        com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository repo =
                new com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository(db);
        for (int i = 0; i < 160; i++) {
            repo.insertSync(new TransactionInput(TransactionType.EXPENSE, 1000 + i, USD,
                    i % 3 == 0 ? longCat : categoryId(db, TransactionType.EXPENSE, "Food"), null,
                    at(2026, 10, 1, 0, 0) + i * 3600_000L, i % 4 == 0 ? longNote : "note " + i));
        }
        long now = at(2026, 10, 22, 12, 0);
        ReportData data = octoberReport(now);
        List<TransactionRow> rows = octoberRows(now);
        PdfReportWriter.Labels labels = new PdfReportWriter.Labels(context);

        RecordingSink first = new RecordingSink();
        int pages = PdfReportWriter.render(first, data, rows, labels, 0, UTC, Locale.US, now);
        RecordingSink second = new RecordingSink();
        int pagesAgain = PdfReportWriter.render(second, data, rows, labels, pages, UTC, Locale.US, now);

        assertTrue("expected several pages, got " + pages, pages >= 3);
        assertEquals(pages, pagesAgain);
        assertTrue(second.problems.toString(), second.problems.isEmpty());

        // Every page from the one where the transaction table starts repeats its column headings.
        int tableStart = -1;
        for (int p = 0; p < pages; p++) {
            if (second.pageTexts.get(p).contains(labels.transactions)) {
                tableStart = p;
                break;
            }
        }
        assertTrue(tableStart >= 0);
        for (int p = tableStart; p < pages; p++) {
            List<String> texts = second.pageTexts.get(p);
            assertTrue("page " + (p + 1) + " lacks headings", texts.contains(labels.note) && texts.contains(labels.amount));
            assertTrue(texts.contains(String.format(labels.page, p + 1, pages)));
        }
        // Totals printed are the database totals.
        String expenses = CurrencyFormatter.format(data.expenseMinor, USD);
        assertTrue(second.pageTexts.get(0).contains(expenses));
        assertEquals(db.transactionDao().sumByType(TransactionType.EXPENSE, USD, data.range.start, data.range.endExclusive),
                data.expenseMinor);

        // The real PDF has the same number of pages.
        File file = new File(context.getCacheDir(), "report-test.pdf");
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertEquals(pages, PdfReportWriter.write(out, data, rows, labels, UTC, Locale.US, now));
        }
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            assertEquals(pages, renderer.getPageCount());
            PdfRenderer.Page page = renderer.openPage(0);
            assertEquals(PdfReportWriter.PAGE_WIDTH, page.getWidth());
            assertEquals(PdfReportWriter.PAGE_HEIGHT, page.getHeight());
            page.close();
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void emptyReportsStillProduceAValidOnePagePdf() throws Exception {
        long now = at(2026, 10, 22, 12, 0);
        ReportData data = octoberReport(now);
        assertTrue(data.isEmpty());
        RecordingSink sink = new RecordingSink();
        PdfReportWriter.Labels labels = new PdfReportWriter.Labels(context);
        assertEquals(1, PdfReportWriter.render(sink, data, new ArrayList<>(), labels, 1, UTC, Locale.US, now));
        assertTrue(sink.pageTexts.get(0).contains(labels.empty));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(1, PdfReportWriter.write(out, data, null, labels, UTC, Locale.US, now));
        assertNotEquals(0, out.size());
    }

    // ---- Sharing ---------------------------------------------------------------------

    @Test
    public void shareIntentUsesTheContentUriWithAReadGrant() {
        Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.pdf");
        Intent intent = ExportActions.shareIntent(uri, "application/pdf");
        assertEquals(Intent.ACTION_SEND, intent.getAction());
        assertEquals("application/pdf", intent.getType());
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        ClipData clip = intent.getClipData();
        assertEquals(uri, clip.getItemAt(0).getUri());
        assertEquals("content", uri.getScheme());
    }
}
