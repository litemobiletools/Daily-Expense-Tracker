package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;

import java.util.TimeZone;

/** Date-grouped transaction list. Updates are diffed, so only changed rows are rebound. */
public class HistoryAdapter extends ListAdapter<HistoryItem, RecyclerView.ViewHolder> {

    public interface OnRowClickListener {
        void onRowClick(@NonNull TransactionRow row);
    }

    private static final DiffUtil.ItemCallback<HistoryItem> DIFF = new DiffUtil.ItemCallback<HistoryItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull HistoryItem a, @NonNull HistoryItem b) {
            return a.viewType == b.viewType && a.stableKey() == b.stableKey();
        }

        @Override
        public boolean areContentsTheSame(@NonNull HistoryItem a, @NonNull HistoryItem b) {
            if (a.row == null || b.row == null) {
                return a.dayStart == b.dayStart;
            }
            return a.row.sameContentAs(b.row);
        }
    };

    private final OnRowClickListener listener;
    private final TimeZone zone = TimeZone.getDefault();

    public HistoryAdapter(@NonNull OnRowClickListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).viewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == HistoryItem.TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_history_header, parent, false));
        }
        return new RowHolder(inflater.inflate(R.layout.item_transaction, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        HistoryItem item = getItem(position);
        long now = System.currentTimeMillis();
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(headerLabel(holder.itemView, item.dayStart, now));
        } else if (item.row != null) {
            final TransactionRow row = item.row;
            TransactionRowBinder.bind(holder.itemView, row, false, now);
            holder.itemView.setOnClickListener(v -> listener.onRowClick(row));
        }
    }

    private String headerLabel(View view, long dayStart, long now) {
        switch (HistoryGrouper.dayKind(dayStart, now, zone)) {
            case HistoryGrouper.DAY_TODAY:
                return view.getContext().getString(R.string.day_today);
            case HistoryGrouper.DAY_YESTERDAY:
                return view.getContext().getString(R.string.day_yesterday);
            default:
                return DateUtils.formatDateTime(view.getContext(), dayStart,
                        DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR);
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.header_title);
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        RowHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
