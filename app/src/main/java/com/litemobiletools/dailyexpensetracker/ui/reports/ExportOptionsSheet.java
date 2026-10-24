package com.litemobiletools.dailyexpensetracker.ui.reports;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.ChipGroup;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.export.ExportRequest;
import com.litemobiletools.dailyexpensetracker.model.ReportRange;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.ui.transactions.ExpandedBottomSheet;

/** Lets the user choose what to export; the parent fragment then opens the system save dialog. */
public class ExportOptionsSheet extends ExpandedBottomSheet {

    public static final String RESULT_KEY = "export_options_result";
    public static final String RESULT_REQUEST = "request";

    private static final String TAG = "export_options";
    private static final String ARG_RANGE = "range";
    private static final String ARG_CURRENCY = "currency";
    private static final String ARG_BUDGETS = "budgets_available";

    public static void show(@NonNull FragmentManager manager, @NonNull ReportRange range,
                            @NonNull String currency, boolean budgetsAvailable) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        ExportOptionsSheet sheet = new ExportOptionsSheet();
        Bundle args = new Bundle();
        args.putSerializable(ARG_RANGE, range);
        args.putString(ARG_CURRENCY, currency);
        args.putBoolean(ARG_BUDGETS, budgetsAvailable);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_export_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ReportRange range = (ReportRange) requireArguments().getSerializable(ARG_RANGE);
        String currency = requireArguments().getString(ARG_CURRENCY, "");
        boolean budgetsAvailable = requireArguments().getBoolean(ARG_BUDGETS);
        if (range == null) {
            dismissAllowingStateLoss();
            return;
        }

        ((TextView) view.findViewById(R.id.export_period)).setText(getString(R.string.export_period_currency_line,
                getString(R.string.reports_period_value,
                        DateUtils.formatDateTime(requireContext(), range.start, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR),
                        DateUtils.formatDateTime(requireContext(), range.lastInstant(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR)),
                currency));

        RadioGroup kinds = view.findViewById(R.id.export_kind);
        View transactionOptions = view.findViewById(R.id.export_transaction_options);
        MaterialCheckBox pdfTransactions = view.findViewById(R.id.export_pdf_transactions);
        view.findViewById(R.id.export_kind_budgets).setEnabled(budgetsAvailable);
        view.findViewById(R.id.export_budgets_hint).setVisibility(budgetsAvailable ? View.GONE : View.VISIBLE);
        kinds.setOnCheckedChangeListener((group, checkedId) -> {
            transactionOptions.setVisibility(checkedId == R.id.export_kind_transactions ? View.VISIBLE : View.GONE);
            pdfTransactions.setVisibility(checkedId == R.id.export_kind_pdf ? View.VISIBLE : View.GONE);
        });

        ChipGroup scope = view.findViewById(R.id.export_scope);
        ChipGroup type = view.findViewById(R.id.export_type);
        view.findViewById(R.id.export_go).setOnClickListener(v -> {
            int checked = kinds.getCheckedRadioButtonId();
            ExportRequest.Kind kind = checked == R.id.export_kind_categories ? ExportRequest.Kind.CATEGORY_CSV
                    : checked == R.id.export_kind_budgets ? ExportRequest.Kind.BUDGET_CSV
                    : checked == R.id.export_kind_pdf ? ExportRequest.Kind.PDF
                    : ExportRequest.Kind.TRANSACTIONS_CSV;
            int typeId = type.getCheckedChipId();
            TransactionType selectedType = typeId == R.id.export_type_income ? TransactionType.INCOME
                    : typeId == R.id.export_type_expense ? TransactionType.EXPENSE : null;
            ExportRequest request = new ExportRequest(kind, range, currency,
                    scope.getCheckedChipId() == R.id.export_scope_all, selectedType, pdfTransactions.isChecked());
            Bundle result = new Bundle();
            result.putSerializable(RESULT_REQUEST, request);
            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
            dismissAllowingStateLoss();
        });
    }
}
