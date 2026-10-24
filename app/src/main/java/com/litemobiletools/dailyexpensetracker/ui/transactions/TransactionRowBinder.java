package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;
import com.litemobiletools.dailyexpensetracker.util.CurrencyFormatter;

import java.util.ArrayList;
import java.util.List;

/** Fills an {@code item_transaction} view. Shared by the dashboard and the history list so rows look identical. */
public final class TransactionRowBinder {

    private TransactionRowBinder() {
    }

    /**
     * @param showDate true to show a relative date ("2 days ago") in the subtitle (dashboard),
     *                 false to show the time of day (history, where date headings exist)
     */
    public static void bind(@NonNull View view, @NonNull TransactionRow row, boolean showDate, long nowMillis) {
        Context context = view.getContext();
        boolean isIncome = row.type == TransactionType.INCOME;
        int typeColor = ContextCompat.getColor(context, isIncome ? R.color.income : R.color.expense);
        int iconColor = row.categoryColorArgb != null ? row.categoryColorArgb : typeColor;

        ImageView icon = view.findViewById(R.id.transaction_icon);
        icon.setImageResource(CategoryIcons.resFor(row.categoryIconKey));
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(iconColor));
        icon.setBackgroundResource(R.drawable.bg_circle_solid);
        icon.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(iconColor, 0x2E)));

        boolean hasNote = row.note != null && !row.note.isEmpty();
        String title = hasNote ? row.note : row.categoryName;
        String when = showDate
                ? DateUtils.getRelativeTimeSpanString(row.transactionTimestamp, nowMillis,
                        DateUtils.DAY_IN_MILLIS).toString()
                : android.text.format.DateFormat.getTimeFormat(context)
                        .format(new java.util.Date(row.transactionTimestamp));

        List<String> parts = new ArrayList<>();
        if (hasNote) {
            parts.add(row.categoryName);
        }
        if (row.paymentMethodName != null) {
            parts.add(row.paymentMethodName);
        }
        parts.add(when);

        String amount = CurrencyFormatter.format(row.amountMinor, row.currencyCode);
        ((TextView) view.findViewById(R.id.transaction_title)).setText(title);
        ((TextView) view.findViewById(R.id.transaction_subtitle)).setText(join(parts));
        TextView amountView = view.findViewById(R.id.transaction_amount);
        amountView.setText(context.getString(isIncome ? R.string.signed_income : R.string.signed_expense, amount));
        amountView.setTextColor(typeColor);

        String typeLabel = context.getString(isIncome ? R.string.type_income : R.string.type_expense);
        view.setContentDescription(row.paymentMethodName == null
                ? context.getString(R.string.row_description, typeLabel, title, row.categoryName, amount, when)
                : context.getString(R.string.row_description_payment, typeLabel, title,
                        row.categoryName, amount, when, row.paymentMethodName));
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
