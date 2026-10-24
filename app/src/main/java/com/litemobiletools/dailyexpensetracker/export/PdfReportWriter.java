package com.litemobiletools.dailyexpensetracker.export;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.entity.CategoryTotal;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.BudgetProgress;
import com.litemobiletools.dailyexpensetracker.model.ReportData;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Printable A4 report drawn with {@link PdfDocument}. Layout runs twice through a {@link Sink}: once
 * to count pages (so every footer can say "Page n of N"), then to draw. Text is wrapped to its column
 * (long words are broken), rows never straddle a page, and table headings repeat on every page a table
 * continues onto. All money figures come from {@link ReportData}, the same object the screen shows.
 */
public final class PdfReportWriter {

    public static final int PAGE_WIDTH = 595;   // A4 in PostScript points
    public static final int PAGE_HEIGHT = 842;
    public static final float MARGIN = 40f;
    private static final float FOOTER_HEIGHT = 28f;
    private static final float CELL_PAD = 4f;
    private static final int MAX_CELL_LINES = 8;

    /** Where layout output goes: a real PDF page, a page counter, or a test recorder. */
    public interface Sink {
        void beginPage(int pageNumber);

        void text(@NonNull String text, float x, float baseline, @NonNull Paint paint);

        void line(float x1, float y1, float x2, float y2, @NonNull Paint paint);

        void rect(float left, float top, float right, float bottom, @NonNull Paint paint);

        void endPage();
    }

    /** Localised strings used in the document. */
    public static final class Labels {
        public final String appName;
        public final String title;
        public final String period;
        public final String currencyNote;
        public final String generated;
        public final String income;
        public final String expenses;
        public final String net;
        public final String previous;
        public final String expensesByCategory;
        public final String incomeByCategory;
        public final String budgets;
        public final String transactions;
        public final String category;
        public final String count;
        public final String total;
        public final String share;
        public final String date;
        public final String type;
        public final String payment;
        public final String note;
        public final String amount;
        public final String budget;
        public final String limit;
        public final String spent;
        public final String remaining;
        public final String overall;
        public final String allCategories;
        public final String typeIncome;
        public final String typeExpense;
        public final String empty;
        public final Resources res;
        public final String page;
        public final String noComparison;

        public Labels(@NonNull Context c) {
            appName = c.getString(R.string.app_name);
            title = c.getString(R.string.pdf_title);
            period = c.getString(R.string.pdf_period);
            currencyNote = c.getString(R.string.pdf_currency);
            generated = c.getString(R.string.pdf_generated);
            income = c.getString(R.string.reports_income);
            expenses = c.getString(R.string.reports_expenses);
            net = c.getString(R.string.reports_net);
            previous = c.getString(R.string.pdf_previous);
            expensesByCategory = c.getString(R.string.reports_expense_categories);
            incomeByCategory = c.getString(R.string.reports_income_categories);
            budgets = c.getString(R.string.reports_budgets);
            transactions = c.getString(R.string.pdf_transactions);
            category = c.getString(R.string.pdf_col_category);
            count = c.getString(R.string.pdf_col_count);
            total = c.getString(R.string.pdf_col_total);
            share = c.getString(R.string.pdf_col_share);
            date = c.getString(R.string.pdf_col_date);
            type = c.getString(R.string.pdf_col_type);
            payment = c.getString(R.string.pdf_col_payment);
            note = c.getString(R.string.pdf_col_note);
            amount = c.getString(R.string.pdf_col_amount);
            budget = c.getString(R.string.pdf_col_budget);
            limit = c.getString(R.string.pdf_col_limit);
            spent = c.getString(R.string.pdf_col_spent);
            remaining = c.getString(R.string.pdf_col_remaining);
            overall = c.getString(R.string.budgets_overall);
            allCategories = c.getString(R.string.pdf_all_categories);
            typeIncome = c.getString(R.string.type_income);
            typeExpense = c.getString(R.string.type_expense);
            empty = c.getString(R.string.pdf_empty);
            res = c.getResources();
            page = c.getString(R.string.pdf_page);
            noComparison = c.getString(R.string.pdf_no_comparison);
        }
    }

    private PdfReportWriter() {
    }

    /** Lays out and writes the PDF. Returns the number of pages. */
    @WorkerThread
    public static int write(@NonNull OutputStream out, @NonNull ReportData data,
                            @Nullable List<TransactionRow> transactions, @NonNull Labels labels,
                            @NonNull TimeZone zone, @NonNull Locale locale, long generatedAt) throws IOException {
        int pages = render(new CountingSink(), data, transactions, labels, 0, zone, locale, generatedAt);
        PdfDocument document = new PdfDocument();
        try {
            render(new PdfSink(document), data, transactions, labels, pages, zone, locale, generatedAt);
            document.writeTo(out);
        } finally {
            document.close();
        }
        return pages;
    }

    /** Runs the layout. {@code totalPages} 0 means unknown (first pass). Returns pages produced. */
    public static int render(@NonNull Sink sink, @NonNull ReportData data, @Nullable List<TransactionRow> transactions,
                             @NonNull Labels labels, int totalPages, @NonNull TimeZone zone,
                             @NonNull Locale locale, long generatedAt) {
        Layout layout = new Layout(sink, labels, totalPages, zone, locale, generatedAt);
        layout.document(data, transactions);
        return layout.finish();
    }

    // ---- Layout ----------------------------------------------------------------------

    private static final class Layout {
        private final Sink sink;
        private final Labels labels;
        private final int totalPages;
        private final DateFormat dateFormat;
        private final DateFormat shortDate;
        private final String generatedText;
        private final float contentWidth = PAGE_WIDTH - 2 * MARGIN;
        private final float bottom = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT;

        private final Paint titlePaint = paint(18f, true, 0xFF0B2545);
        private final Paint subtitlePaint = paint(12f, false, 0xFF333333);
        private final Paint bodyPaint = paint(9.5f, false, 0xFF222222);
        private final Paint bodyBold = paint(9.5f, true, 0xFF111111);
        private final Paint headPaint = paint(9f, true, 0xFF0B2545);
        private final Paint cellPaint = paint(9f, false, 0xFF222222);
        private final Paint cellBold = paint(9f, true, 0xFF111111);
        private final Paint smallPaint = paint(8f, false, 0xFF666666);
        private final Paint sectionPaint = paint(12f, true, 0xFF0B2545);
        private final Paint rulePaint = new Paint();
        private final Paint headerFill = new Paint();

        private int page;
        private float y;
        private final Locale locale;

        Layout(Sink sink, Labels labels, int totalPages, TimeZone zone, Locale locale, long generatedAt) {
            this.sink = sink;
            this.labels = labels;
            this.totalPages = totalPages;
            this.locale = locale;
            dateFormat = DateFormat.getDateInstance(DateFormat.LONG, locale);
            dateFormat.setTimeZone(zone);
            shortDate = DateFormat.getDateInstance(DateFormat.SHORT, locale);
            shortDate.setTimeZone(zone);
            DateFormat stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
            stamp.setTimeZone(zone);
            generatedText = String.format(locale, labels.generated, stamp.format(new Date(generatedAt)));
            rulePaint.setColor(0xFFCCD3DD);
            rulePaint.setStrokeWidth(0.6f);
            headerFill.setColor(0xFFEEF2F7);
            headerFill.setStyle(Paint.Style.FILL);
        }

        private static Paint paint(float size, boolean bold, int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(size);
            p.setColor(color);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
            return p;
        }

        void document(ReportData data, @Nullable List<TransactionRow> transactions) {
            newPage();
            String currency = data.currencyCode;

            // Title block
            paragraph(labels.appName, titlePaint, 4f);
            paragraph(labels.title, subtitlePaint, 6f);
            String period = String.format(locale, labels.period, dateFormat.format(new Date(data.range.start)),
                    dateFormat.format(new Date(data.range.lastInstant())));
            paragraph(period, bodyBold, 2f);
            paragraph(String.format(locale, labels.currencyNote, currency), bodyPaint, 2f);
            paragraph(generatedText, smallPaint, 10f);

            // Summary
            String[] summaryHead = {"", labels.amount};
            float[] summaryWidths = {contentWidth * 0.6f, contentWidth * 0.4f};
            boolean[] right = {false, true};
            List<String[]> summary = new ArrayList<>();
            summary.add(new String[]{labels.income, money(data.incomeMinor, currency)});
            summary.add(new String[]{labels.expenses, money(data.expenseMinor, currency)});
            summary.add(new String[]{labels.net, signedMoney(data.netMinor(), currency)});
            table(null, summaryHead, summaryWidths, right, summary, true);

            String previous = String.format(locale, labels.previous,
                    dateFormat.format(new Date(data.previousRange.start)),
                    dateFormat.format(new Date(data.previousRange.lastInstant())),
                    money(data.prevIncomeMinor, currency), money(data.prevExpenseMinor, currency));
            paragraph(previous, bodyPaint, 2f);
            if (data.prevIncomeMinor == 0 && data.prevExpenseMinor == 0) {
                paragraph(labels.noComparison, smallPaint, 2f);
            }
            if (data.otherCurrencyCount > 0) {
                paragraph(labels.res.getQuantityString(R.plurals.pdf_other_currencies,
                        data.otherCurrencyCount, data.otherCurrencyCount, currency), smallPaint, 2f);
            }
            y += 8f;

            if (data.isEmpty()) {
                paragraph(labels.empty, bodyBold, 6f);
            }

            categoryTable(labels.expensesByCategory, data.expenseByCategory, data.expenseMinor, currency);
            categoryTable(labels.incomeByCategory, data.incomeByCategory, data.incomeMinor, currency);

            if (data.budgets != null && !data.budgets.isEmpty()) {
                List<String[]> rows = new ArrayList<>();
                for (BudgetProgress p : data.budgets) {
                    rows.add(new String[]{p.isOverall() ? labels.overall : p.categoryName,
                            money(p.limitMinor(), currency), money(p.spentMinor, currency),
                            signedMoney(p.remainingMinor(), currency)});
                }
                float w = contentWidth;
                table(labels.budgets, new String[]{labels.budget, labels.limit, labels.spent, labels.remaining},
                        new float[]{w * 0.4f, w * 0.2f, w * 0.2f, w * 0.2f},
                        new boolean[]{false, true, true, true}, rows, false);
            }

            if (transactions != null) {
                List<String[]> rows = new ArrayList<>(transactions.size());
                for (TransactionRow t : transactions) {
                    boolean income = t.type == TransactionType.INCOME;
                    String amount = money(t.amountMinor, t.currencyCode);
                    rows.add(new String[]{shortDate.format(new Date(t.transactionTimestamp)),
                            income ? labels.typeIncome : labels.typeExpense,
                            t.categoryName,
                            t.paymentMethodName == null ? "" : t.paymentMethodName,
                            t.note == null ? "" : t.note,
                            (income ? "+" : "−") + amount});
                }
                float w = contentWidth;
                table(labels.transactions,
                        new String[]{labels.date, labels.type, labels.category, labels.payment, labels.note, labels.amount},
                        new float[]{w * 0.13f, w * 0.11f, w * 0.18f, w * 0.15f, w * 0.26f, w * 0.17f},
                        new boolean[]{false, false, false, false, false, true}, rows, false);
            }
        }

        private void categoryTable(String title, List<CategoryTotal> totals, long typeTotal, String currency) {
            List<String[]> rows = new ArrayList<>();
            int count = 0;
            for (CategoryTotal t : totals) {
                rows.add(new String[]{t.categoryName, Integer.toString(t.transactionCount),
                        money(t.totalMinor, currency), CsvExporter.share(t.totalMinor, typeTotal) + "%"});
                count += t.transactionCount;
            }
            rows.add(new String[]{labels.allCategories, Integer.toString(count), money(typeTotal, currency),
                    typeTotal > 0 ? "100.0%" : "0.0%"});
            float w = contentWidth;
            table(title, new String[]{labels.category, labels.count, labels.total, labels.share},
                    new float[]{w * 0.46f, w * 0.14f, w * 0.24f, w * 0.16f},
                    new boolean[]{false, true, true, true}, rows, true);
        }

        /** A table whose heading row repeats on every page it continues onto. */
        private void table(@Nullable String title, String[] head, float[] widths, boolean[] rightAlign,
                           List<String[]> rows, boolean boldLastRow) {
            float lineHeight = lineHeight(cellPaint);
            float headHeight = lineHeight + 2 * CELL_PAD;
            float titleHeight = title == null ? 0 : lineHeight(sectionPaint) + 6f;
            float firstRowHeight = rows.isEmpty() ? 0 : rowHeight(rows.get(0), widths, cellPaint);
            // Keep the title, heading and first row together.
            ensureSpace(titleHeight + headHeight + firstRowHeight);
            if (title != null) {
                y += lineHeight(sectionPaint);
                sink.text(title, MARGIN, y - descent(sectionPaint), sectionPaint);
                y += 6f;
            }
            drawHead(head, widths, rightAlign, headHeight);
            for (int r = 0; r < rows.size(); r++) {
                String[] row = rows.get(r);
                boolean bold = boldLastRow && r == rows.size() - 1;
                Paint p = bold ? cellBold : cellPaint;
                float h = rowHeight(row, widths, p);
                if (y + h > bottom) {
                    newPage();
                    if (title != null) {
                        sink.text(title, MARGIN, y + lineHeight(smallPaint) - descent(smallPaint), smallPaint);
                        y += lineHeight(smallPaint) + 4f;
                    }
                    drawHead(head, widths, rightAlign, headHeight);
                }
                float x = MARGIN;
                for (int c = 0; c < row.length; c++) {
                    List<String> lines = wrap(row[c], p, widths[c] - 2 * CELL_PAD);
                    float baseline = y + CELL_PAD + lineHeight - descent(p);
                    for (String line : lines) {
                        float tx = rightAlign[c] ? x + widths[c] - CELL_PAD - p.measureText(line) : x + CELL_PAD;
                        sink.text(line, tx, baseline, p);
                        baseline += lineHeight;
                    }
                    x += widths[c];
                }
                y += h;
                sink.line(MARGIN, y, MARGIN + contentWidth, y, rulePaint);
            }
            y += 12f;
        }

        private void drawHead(String[] head, float[] widths, boolean[] rightAlign, float headHeight) {
            sink.rect(MARGIN, y, MARGIN + contentWidth, y + headHeight, headerFill);
            float x = MARGIN;
            float baseline = y + CELL_PAD + lineHeight(headPaint) - descent(headPaint);
            for (int c = 0; c < head.length; c++) {
                String text = ellipsize(head[c], headPaint, widths[c] - 2 * CELL_PAD);
                float tx = rightAlign[c] ? x + widths[c] - CELL_PAD - headPaint.measureText(text) : x + CELL_PAD;
                sink.text(text, tx, baseline, headPaint);
                x += widths[c];
            }
            y += headHeight;
        }

        private float rowHeight(String[] row, float[] widths, Paint p) {
            int maxLines = 1;
            for (int c = 0; c < row.length; c++) {
                maxLines = Math.max(maxLines, wrap(row[c], p, widths[c] - 2 * CELL_PAD).size());
            }
            return maxLines * lineHeight(p) + 2 * CELL_PAD;
        }

        private void paragraph(String text, Paint p, float spaceAfter) {
            float lh = lineHeight(p);
            for (String line : wrap(text, p, contentWidth)) {
                ensureSpace(lh);
                y += lh;
                sink.text(line, MARGIN, y - descent(p), p);
            }
            y += spaceAfter;
        }

        private void ensureSpace(float height) {
            if (y + height > bottom) {
                newPage();
            }
        }

        private void newPage() {
            if (page > 0) {
                footer();
                sink.endPage();
            }
            page++;
            sink.beginPage(page);
            y = MARGIN;
        }

        private void footer() {
            float lineY = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT + 8f;
            sink.line(MARGIN, lineY, MARGIN + contentWidth, lineY, rulePaint);
            float baseline = PAGE_HEIGHT - MARGIN - 4f;
            String left = ellipsize(labels.appName + " · " + generatedText, smallPaint, contentWidth * 0.7f);
            sink.text(left, MARGIN, baseline, smallPaint);
            String pageText = totalPages > 0 ? String.format(locale, labels.page, page, totalPages)
                    : String.format(locale, labels.page, page, page);
            sink.text(pageText, MARGIN + contentWidth - smallPaint.measureText(pageText), baseline, smallPaint);
        }

        int finish() {
            footer();
            sink.endPage();
            return page;
        }

        private String money(long minor, String currency) {
            return CurrencyFormatter.format(minor, currency);
        }

        private String signedMoney(long minor, String currency) {
            return minor < 0 ? "−" + CurrencyFormatter.format(-minor, currency)
                    : CurrencyFormatter.format(minor, currency);
        }
    }

    // ---- Text helpers ----------------------------------------------------------------

    static float lineHeight(Paint p) {
        Paint.FontMetrics m = p.getFontMetrics();
        return (m.descent - m.ascent) * 1.15f;
    }

    static float descent(Paint p) {
        return p.getFontMetrics().descent;
    }

    /** Wraps to {@code width}, breaking words longer than a line; at most {@link #MAX_CELL_LINES} lines. */
    @NonNull
    static List<String> wrap(@Nullable String text, @NonNull Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        for (String paragraph : text.replace("\r", "").split("\n")) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (paint.measureText(candidate) <= width) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                String rest = word;
                while (paint.measureText(rest) > width && rest.length() > 1) {
                    int n = Math.max(1, paint.breakText(rest, true, width, null));
                    lines.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                current.append(rest);
            }
            lines.add(current.toString());
        }
        if (lines.size() > MAX_CELL_LINES) {
            List<String> capped = new ArrayList<>(lines.subList(0, MAX_CELL_LINES));
            capped.set(MAX_CELL_LINES - 1, ellipsize(capped.get(MAX_CELL_LINES - 1) + "…", paint, width));
            return capped;
        }
        return lines;
    }

    @NonNull
    static String ellipsize(@NonNull String text, @NonNull Paint paint, float width) {
        if (paint.measureText(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int n = paint.breakText(text, true, Math.max(0, width - paint.measureText(ellipsis)), null);
        return text.substring(0, Math.max(0, n)) + ellipsis;
    }

    // ---- Sinks -----------------------------------------------------------------------

    /** First pass: only counts pages. */
    static final class CountingSink implements Sink {
        @Override public void beginPage(int pageNumber) { }
        @Override public void text(@NonNull String text, float x, float baseline, @NonNull Paint paint) { }
        @Override public void line(float x1, float y1, float x2, float y2, @NonNull Paint paint) { }
        @Override public void rect(float l, float t, float r, float b, @NonNull Paint paint) { }
        @Override public void endPage() { }
    }

    /** Second pass: draws onto real PDF pages. */
    static final class PdfSink implements Sink {
        private final PdfDocument document;
        private PdfDocument.Page page;
        private Canvas canvas;

        PdfSink(PdfDocument document) {
            this.document = document;
        }

        @Override
        public void beginPage(int pageNumber) {
            page = document.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create());
            canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
        }

        @Override
        public void text(@NonNull String text, float x, float baseline, @NonNull Paint paint) {
            canvas.drawText(text, x, baseline, paint);
        }

        @Override
        public void line(float x1, float y1, float x2, float y2, @NonNull Paint paint) {
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        @Override
        public void rect(float l, float t, float r, float b, @NonNull Paint paint) {
            canvas.drawRect(l, t, r, b, paint);
        }

        @Override
        public void endPage() {
            document.finishPage(page);
            page = null;
            canvas = null;
        }
    }
}
