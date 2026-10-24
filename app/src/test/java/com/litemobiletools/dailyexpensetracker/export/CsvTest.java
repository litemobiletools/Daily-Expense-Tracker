package com.litemobiletools.dailyexpensetracker.export;

import static org.junit.Assert.assertEquals;

import com.litemobiletools.dailyexpensetracker.model.ReportPreset;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

import org.junit.Test;

import java.io.StringWriter;
import java.util.Calendar;
import java.util.TimeZone;

public class CsvTest {

    @Test
    public void plainValuesAreUnchanged() {
        assertEquals("Groceries", Csv.text("Groceries"));
        assertEquals("12.50", Csv.plain("12.50"));
        assertEquals("", Csv.text(null));
        assertEquals("", Csv.text(""));
    }

    @Test
    public void commasQuotesAndLineBreaksAreQuoted() {
        assertEquals("\"Rent, October\"", Csv.text("Rent, October"));
        assertEquals("\"He said \"\"hi\"\"\"", Csv.text("He said \"hi\""));
        assertEquals("\"line1\nline2\"", Csv.text("line1\nline2"));
        assertEquals("\"line1\r\nline2\"", Csv.text("line1\r\nline2"));
    }

    @Test
    public void formulaLikeTextIsNeutralised() {
        assertEquals("'=SUM(A1:A9)", Csv.text("=SUM(A1:A9)"));
        assertEquals("'+1234", Csv.text("+1234"));
        assertEquals("'-2+3", Csv.text("-2+3"));
        assertEquals("'@cmd", Csv.text("@cmd"));
        assertEquals("'\tTabbed", Csv.text("\tTabbed"));
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", Csv.text("=HYPERLINK(\"http://x\",\"y\")"));
        assertEquals("'＝1+1", Csv.text("＝1+1")); // full-width equals sign
        // Only the first character matters.
        assertEquals("a=b", Csv.text("a=b"));
    }

    @Test
    public void numericColumnsAreNotNeutralised() {
        assertEquals("-12.50", Csv.plain("-12.50"));
    }

    @Test
    public void rowsUseCommasAndCrlf() throws Exception {
        StringWriter w = new StringWriter();
        Csv.row(w, "a", Csv.text("b,c"), "");
        Csv.header(w, new String[]{"x", "y"});
        assertEquals("a,\"b,c\",\r\nx,y\r\n", w.toString());
    }

    @Test
    public void shareIsOneDecimalAndSafeForZero() {
        assertEquals("33.3", CsvExporter.share(1, 3));
        assertEquals("100.0", CsvExporter.share(5, 5));
        assertEquals("0.0", CsvExporter.share(5, 0));
    }

    @Test
    public void suggestedNamesDescribeTheExport() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar c = Calendar.getInstance(utc);
        c.clear();
        c.set(2026, Calendar.OCTOBER, 1);
        long start = c.getTimeInMillis();
        c.set(2026, Calendar.NOVEMBER, 1);
        ReportRange october = new ReportRange(ReportPreset.THIS_MONTH, start, c.getTimeInMillis());
        assertEquals("expenses_2026-10-01_to_2026-10-31.csv", new ExportRequest(ExportRequest.Kind.TRANSACTIONS_CSV,
                october, "USD", false, TransactionType.EXPENSE, false).suggestedName(utc));
        assertEquals("transactions_all_time.csv", new ExportRequest(ExportRequest.Kind.TRANSACTIONS_CSV,
                october, "USD", true, null, false).suggestedName(utc));
        assertEquals("financial_report_2026-10-01_to_2026-10-31.pdf", new ExportRequest(ExportRequest.Kind.PDF,
                october, "USD", false, null, true).suggestedName(utc));
        assertEquals("application/pdf", new ExportRequest(ExportRequest.Kind.PDF, october, "USD", false, null, true).mimeType());
        assertEquals("text/csv", new ExportRequest(ExportRequest.Kind.CATEGORY_CSV, october, "USD", false, null, false).mimeType());
    }
}
