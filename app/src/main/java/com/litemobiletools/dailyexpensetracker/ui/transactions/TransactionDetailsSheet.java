package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.FragmentManager;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

/** Read-only details of one transaction with Edit and Delete. Always reloads the record by id. */
public class TransactionDetailsSheet extends ExpandedBottomSheet {

    private static final String ARG_ID = "transaction_id";
    private static final String TAG = "transaction_details";
    private static final int DATE_TIME_FLAGS = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
            | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY;

    public static void show(@NonNull FragmentManager manager, long transactionId) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) {
            return;
        }
        TransactionDetailsSheet sheet = new TransactionDetailsSheet();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, transactionId);
        sheet.setArguments(args);
        sheet.show(manager, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_transaction_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TransactionRepository.get(requireContext()).getRow(requireArguments().getLong(ARG_ID),
                new RepoCallback<TransactionRow>() {
                    @Override
                    public void onSuccess(TransactionRow row) {
                        if (getView() == null) {
                            return;
                        }
                        if (row == null) {
                            showStatus(R.string.details_not_found);
                        } else {
                            bind(row);
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        if (getView() != null) {
                            showStatus(R.string.details_load_failed);
                        }
                    }
                });
    }

    private void showStatus(@StringRes int message) {
        View view = requireView();
        view.findViewById(R.id.details_header).setVisibility(View.GONE);
        TextView status = view.findViewById(R.id.details_status);
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void bind(@NonNull TransactionRow row) {
        View view = requireView();
        boolean isIncome = row.type == TransactionType.INCOME;
        int typeColor = ContextCompat.getColor(requireContext(), isIncome ? R.color.income : R.color.expense);
        int iconColor = row.categoryColorArgb != null ? row.categoryColorArgb : typeColor;
        String amount = CurrencyFormatter.format(row.amountMinor, row.currencyCode);
        String typeLabel = getString(isIncome ? R.string.type_income : R.string.type_expense);

        ImageView icon = view.findViewById(R.id.details_icon);
        icon.setImageResource(CategoryIcons.resFor(row.categoryIconKey));
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(iconColor));
        icon.setBackgroundResource(R.drawable.bg_circle_solid);
        icon.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(iconColor, 0x2E)));
        ((TextView) view.findViewById(R.id.details_type)).setText(typeLabel);
        TextView amountView = view.findViewById(R.id.details_amount);
        amountView.setText(getString(isIncome ? R.string.signed_income : R.string.signed_expense, amount));
        amountView.setTextColor(typeColor);
        view.findViewById(R.id.details_header).setContentDescription(typeLabel + ", " + amount);

        LinearLayout rows = view.findViewById(R.id.details_rows);
        rows.removeAllViews();
        addRow(rows, R.string.details_category, row.categoryName);
        addRow(rows, R.string.details_date, DateUtils.formatDateTime(requireContext(),
                row.transactionTimestamp, DATE_TIME_FLAGS));
        if (row.paymentMethodName != null) {
            addRow(rows, R.string.details_payment, row.paymentMethodName);
        }
        if (row.note != null && !row.note.isEmpty()) {
            addRow(rows, R.string.details_note, row.note);
        }
        addRow(rows, R.string.details_created, DateUtils.formatDateTime(requireContext(),
                row.createdAt, DATE_TIME_FLAGS));
        if (row.updatedAt != row.createdAt) {
            addRow(rows, R.string.details_modified, DateUtils.formatDateTime(requireContext(),
                    row.updatedAt, DATE_TIME_FLAGS));
        }

        view.findViewById(R.id.details_actions).setVisibility(View.VISIBLE);
        view.findViewById(R.id.details_edit).setOnClickListener(v -> {
            TransactionActions.edit(requireContext(), row.id);
            dismissAllowingStateLoss();
        });
        view.findViewById(R.id.details_delete).setOnClickListener(v ->
                TransactionActions.confirmDelete(requireActivity(), row, this::dismissAllowingStateLoss));
    }

    private void addRow(@NonNull LinearLayout parent, @StringRes int label, @NonNull String value) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_detail_row, parent, false);
        ((TextView) row.findViewById(R.id.detail_label)).setText(label);
        ((TextView) row.findViewById(R.id.detail_value)).setText(value);
        row.setContentDescription(getString(label) + ", " + value);
        parent.addView(row);
    }
}
