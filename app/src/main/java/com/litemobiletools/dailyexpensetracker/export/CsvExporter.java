package com.litemobiletools.dailyexpensetracker.export;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * CSV exports. All files are UTF-8 with a byte-order mark, comma separated, CRLF line endings,
 * and a header row. Formats (stable, documented):
 *
 * <ul>
 *   <li>Dates {@code yyyy-MM-dd} and times {@code HH:mm} in the device's time zone; UTC instants
 *       as ISO 8601 {@code yyyy-MM-ddTHH:mm:ssZ}.</li>
 *   <li>Amounts as plain decimals with the currency's own number of decimals and a dot separator,
 *       no grouping and no symbol (12.50 USD, 500 JPY). {@code amount} is always positive;
 *       {@code signed_amount} is negative for expenses. Every row carries its ISO 4217 currency code.</li>
 * </ul>
 *
 * <b>Transactions</b> ({@link #TRANSACTION_HEADER}): id, date, time, timestamp_utc, type
 * (INCOME/EXPENSE), amount, signed_amount, currency, category, payment_method, note, created_utc,
 * updated_utc. Rows are oldest first and every currency is included.
 * <br><b>Category summary</b> ({@link #CATEGORY_HEADER}): one row per category and type for the
 * period plus an "All categories" total row per type, in the report currency.
 * <br><b>Budget summary</b> ({@link #BUDGET_HEADER}): one row per budget for the period.
 */
public final class CsvExporter {

    public static final String[] TRANSACTION_HEADER = {"id", "date", "time", "timestamp_utc", "type",
            "amount", "signed_amount", "currency", "category", "payment_method", "note", "created_utc",
            "updated_utc"};
    public static final String[] CATEGORY_HEADER = {"period_start", "period_end", "currency", "type",
            "category", "transactions", "total", "share_percent"};
    public static final String[] BUDGET_HEADER = {"period_type", "period_start", "period_end", "currency",
            "budget", "limit", "spent", "remaining", "percent_used", "status"};

    /** Label used for the per-type total rows in the category summary. */
    public static final String ALL_CATEGORIES = "All categories";
    public static final String OVERALL_BUDGET = "Overall";

    private static final int PAGE_SIZE = 500;

    private CsvExporter() {
    }

    /**
     * Streams transactions page by page (never the whole table in memory).
     *
     * @param type null for both types
     * @param startInclusive null for no lower bound (all time)
     * @return the number of transaction rows written
     */
    @WorkerThread
    public static long writeTransactions(@NonNull AppDatabase db, @NonNull OutputStream out,
                                         @Nullable TransactionType type, @Nullable Long startInclusive,
                                         @Nullable Long endExclusive, @NonNull TimeZone zone) throws IOException {
        Writer writer = writer(out);
        Csv.header(writer, TRANSACTION_HEADER);
        SimpleDateFormat date = format("yyyy-MM-dd", zone);
        SimpleDateFormat time = format("HH:mm", zone);
        SimpleDateFormat utc = format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"));
        long written = 0;
        long afterTimestamp = Long.MIN_VALUE;
        long afterId = Long.MIN_VALUE;
        while (true) {
            List<TransactionRow> page = db.transactionDao().exportPage(type, null, startInclusive,
                    endExclusive, afterTimestamp, afterId, PAGE_SIZE);
            for (TransactionRow row : page) {
                String amount = CurrencyFormatter.plainAmount(row.amountMinor, row.currencyCode);
                Date when = new Date(row.transactionTimestamp);
                Csv.row(writer,
                        Long.toString(row.id),
                        date.format(when),
                        time.format(when),
                        utc.format(when),
                        row.type.name(),
                        amount,
                        row.type == TransactionType.EXPENSE ? "-" + amount : amount,
                        Csv.plain(row.currencyCode),
                        Csv.text(row.categoryName),
                        Csv.text(row.paymentMethodName),
                        Csv.text(row.note),
                        utc.format(new Date(row.createdAt)),
                        utc.format(new Date(row.updatedAt)));
                written++;
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            TransactionRow last = page.get(page.size() - 1);
            afterTimestamp = last.transactionTimestamp;
            afterId = last.id;
        }
        writer.flush();
        return written;
    }

    /** @return the number of data rows written (categories plus total rows) */
    @WorkerThread
    public static int writeCategorySummary(@NonNull ReportData data, @NonNull OutputStream out,
                                           @NonNull TimeZone zone) throws IOException {
        Writer writer = writer(out);
        Csv.header(writer, CATEGORY_HEADER);
        SimpleDateFormat date = format("yyyy-MM-dd", zone);
        String start = date.format(new Date(data.range.start));
        String end = date.format(new Date(data.range.lastInstant()));
        int rows = 0;
        rows += writeCategoryBlock(writer, start, end, data.currencyCode, TransactionType.EXPENSE,
                data.expenseByCategory, data.expenseMinor);
        rows += writeCategoryBlock(writer, start, end, data.currencyCode, TransactionType.INCOME,
                data.incomeByCategory, data.incomeMinor);
        writer.flush();
        return rows;
    }

    private static int writeCategoryBlock(Writer writer, String start, String end, String currency,
                                          TransactionType type, List<CategoryTotal> totals, long typeTotal)
            throws IOException {
        int rows = 0;
        int count = 0;
        for (CategoryTotal total : totals) {
            Csv.row(writer, start, end, Csv.plain(currency), type.name(), Csv.text(total.categoryName),
                    Integer.toString(total.transactionCount),
                    CurrencyFormatter.plainAmount(total.totalMinor, currency),
                    share(total.totalMinor, typeTotal));
            count += total.transactionCount;
            rows++;
        }
        Csv.row(writer, start, end, Csv.plain(currency), type.name(), Csv.text(ALL_CATEGORIES),
                Integer.toString(count), CurrencyFormatter.plainAmount(typeTotal, currency),
                typeTotal > 0 ? "100.0" : "0.0");
        return rows + 1;
    }

    /** @return the number of budget rows written; zero when the period has no budgets */
    @WorkerThread
    public static int writeBudgetSummary(@NonNull ReportData data, @NonNull OutputStream out,
                                         @NonNull TimeZone zone) throws IOException {
        Writer writer = writer(out);
        Csv.header(writer, BUDGET_HEADER);
        SimpleDateFormat date = format("yyyy-MM-dd", zone);
        int rows = 0;
        if (data.budgets != null) {
            for (BudgetProgress p : data.budgets) {
                String currency = p.budget.currencyCode;
                Csv.row(writer, p.budget.period.name(),
                        date.format(new Date(p.periodStart)),
                        date.format(new Date(p.periodEndExclusive - 1)),
                        Csv.plain(currency),
                        Csv.text(p.isOverall() ? OVERALL_BUDGET : p.categoryName),
                        CurrencyFormatter.plainAmount(p.limitMinor(), currency),
                        CurrencyFormatter.plainAmount(p.spentMinor, currency),
                        signedPlain(p.remainingMinor(), currency),
                        Integer.toString(p.percent()),
                        p.status().name());
                rows++;
            }
        }
        writer.flush();
        return rows;
    }

    // ---- helpers ---------------------------------------------------------------------

    private static Writer writer(OutputStream out) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write(Csv.BOM);
        return writer;
    }

    private static SimpleDateFormat format(String pattern, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(zone);
        return f;
    }

    private static String signedPlain(long minor, String currency) {
        return minor < 0 ? "-" + CurrencyFormatter.plainAmount(-minor, currency)
                : CurrencyFormatter.plainAmount(minor, currency);
    }

    /** Share of {@code part} in {@code whole} as a percentage with one decimal (display only). */
    static String share(long part, long whole) {
        if (whole <= 0) {
            return "0.0";
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP).toPlainString();
    }
}
