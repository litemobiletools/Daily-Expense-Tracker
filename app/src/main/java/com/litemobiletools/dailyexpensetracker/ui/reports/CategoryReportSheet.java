package com.litemobiletools.dailyexpensetracker.ui.reports;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.chip.ChipGroup;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.DatabaseExecutor;
import com.litemobiletools.dailyexpensetracker.data.HistoryPage;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.ReportRepository;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.main.MainActivity;
import com.litemobiletools.dailyexpensetracker.ui.transactions.ExpandedBottomSheet;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionDetailsSheet;
import com.litemobiletools.dailyexpensetracker.ui.transactions.TransactionRowBinder;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One category in the reporting period: its total and its transactions (sortable by amount or date).
 * Rows open the existing details sheet; the button opens the existing Transactions screen pre-filtered.
 */
public class CategoryReportSheet extends ExpandedBottomSheet {

    private static final String TAG = "category_report";
    private static final String ARG_ID = "category_id";
    private static final String ARG_NAME = "name";
    private static final String ARG_TYPE = "type";
    private static final String ARG_RANGE = "range";
    private static final String ARG_CURRENCY = "currency";
    private static final int LIMIT = 200;

    public static void show(@NonNull FragmentManager manager, long categoryId, @NonNull String name,
                            @NonNull TransactionType type, @NonNull ReportRange range, @NonNull String currency) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        CategoryReportSheet sheet = new CategoryReportSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, categoryId);
        args.putString(ARG_NAME, name);
        args.putString(ARG_TYPE, type.name());
        args.putSerializable(ARG_RANGE, range);
        args.putString(ARG_CURRENCY, currency);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    private static final class Detail {
        long totalMinor;
        int count;
        List<TransactionRow> rows = new ArrayList<>();
    }

    private final List<TransactionRow> rows = new ArrayList<>();
    private boolean byAmount = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_category_report, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        long categoryId = args.getLong(ARG_ID);
        TransactionType type = TransactionType.valueOf(args.getString(ARG_TYPE, TransactionType.EXPENSE.name()));
        ReportRange range = (ReportRange) args.getSerializable(ARG_RANGE);
        String currency = args.getString(ARG_CURRENCY, "");
        if (range == null) {
            dismissAllowingStateLoss();
            return;
        }
        ((TextView) view.findViewById(R.id.category_report_title)).setText(args.getString(ARG_NAME, ""));
        ((TextView) view.findViewById(R.id.category_report_period)).setText(getString(R.string.reports_period_value,
                DateUtils.formatDateTime(requireContext(), range.start, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR),
                DateUtils.formatDateTime(requireContext(), range.lastInstant(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR)));

        ChipGroup sort = view.findViewById(R.id.category_report_sort);
        sort.setOnCheckedStateChangeListener((group, ids) -> {
            byAmount = ids.isEmpty() || ids.get(0) == R.id.category_report_sort_amount;
            renderRows();
        });

        TransactionFilter filter = new TransactionFilter();
        filter.type = type;
        filter.categoryId = categoryId;
        filter.startMillis = range.start;
        filter.endExclusiveMillis = range.endExclusive;
        view.findViewById(R.id.category_report_open).setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).openTransactions(filter);
            }
            dismissAllowingStateLoss();
        });

        final AppDatabase db = AppDatabase.getInstance(requireContext());
        DatabaseExecutor.run(() -> {
            Detail detail = new Detail();
            detail.totalMinor = new ReportRepository(db).categoryTotalSync(type, categoryId, currency, range);
            HistoryPage page = new TransactionRepository(db).loadHistorySync(filter, currency, LIMIT, 0, true);
            detail.count = page.totalCount;
            detail.rows = page.rows;
            return detail;
        }, new RepoCallback<Detail>() {
            @Override
            public void onSuccess(Detail detail) {
                View root = getView();
                if (root == null) {
                    return;
                }
                TextView total = root.findViewById(R.id.category_report_total);
                total.setText(getString(R.string.category_report_total, currency,
                        CurrencyFormatter.format(detail.totalMinor, currency)));
                total.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                        type == TransactionType.INCOME ? R.color.income : R.color.expense));
                rows.clear();
                rows.addAll(detail.rows);
                TextView status = root.findViewById(R.id.category_report_status);
                if (detail.count == 0) {
                    status.setText(R.string.category_report_empty);
                    status.setVisibility(View.VISIBLE);
                } else if (detail.count > detail.rows.size()) {
                    status.setText(getString(R.string.category_report_more,
                            getResources().getQuantityString(
                                    R.plurals.category_report_more_shown_of_total,
                                    detail.count, detail.rows.size(), detail.count)));
                    status.setVisibility(View.VISIBLE);
                }
                renderRows();
            }

            @Override
            public void onError(@NonNull Exception error) {
                View root = getView();
                if (root != null) {
                    TextView status = root.findViewById(R.id.category_report_status);
                    status.setText(R.string.details_load_failed);
                    status.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void renderRows() {
        View root = getView();
        if (root == null) {
            return;
        }
        List<TransactionRow> sorted = new ArrayList<>(rows);
        if (byAmount) {
            Collections.sort(sorted, (a, b) -> {
                int byValue = Long.compare(b.amountMinor, a.amountMinor);
                return byValue != 0 ? byValue : Long.compare(b.transactionTimestamp, a.transactionTimestamp);
            });
        }
        // Rows arrive newest first already, which is the date order.
        LinearLayout container = root.findViewById(R.id.category_report_rows);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        long now = System.currentTimeMillis();
        for (TransactionRow row : sorted) {
            View item = inflater.inflate(R.layout.item_transaction, container, false);
            TransactionRowBinder.bind(item, row, true, now);
            final long id = row.id;
            item.setOnClickListener(v -> TransactionDetailsSheet.show(getParentFragmentManager(), id));
            container.addView(item);
        }
    }
}
