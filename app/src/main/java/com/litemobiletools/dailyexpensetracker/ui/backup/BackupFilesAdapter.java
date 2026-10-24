package com.litemobiletools.dailyexpensetracker.ui.backup;

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
import com.litemobiletools.dailyexpensetracker.backup.BackupFileNames;
import com.litemobiletools.dailyexpensetracker.drive.DriveBackupFileInfo;

import java.util.Locale;

/**
 * The list of backup files found in the app's hidden Drive folder, newest first as Drive reports
 * them. Shows only when the backup was made and how big it is — never any backup content.
 */
public class BackupFilesAdapter extends ListAdapter<DriveBackupFileInfo, BackupFilesAdapter.RowHolder> {

    public interface OnBackupClickListener {
        void onBackupClick(@NonNull DriveBackupFileInfo file);
    }

    private static final DiffUtil.ItemCallback<DriveBackupFileInfo> DIFF =
            new DiffUtil.ItemCallback<DriveBackupFileInfo>() {
                @Override
                public boolean areItemsTheSame(@NonNull DriveBackupFileInfo a,
                                               @NonNull DriveBackupFileInfo b) {
                    return a.id.equals(b.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull DriveBackupFileInfo a,
                                                  @NonNull DriveBackupFileInfo b) {
                    return a.sizeBytes == b.sizeBytes && a.modifiedTimeMs == b.modifiedTimeMs
                            && a.name.equals(b.name);
                }
            };

    private final OnBackupClickListener listener;

    public BackupFilesAdapter(@NonNull OnBackupClickListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RowHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_backup_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        final DriveBackupFileInfo file = getItem(position);
        long when = BackupFileNames.timestampFromName(file.name);
        if (when == 0) {
            when = file.modifiedTimeMs;
        }
        holder.title.setText(when != 0
                ? DateUtils.formatDateTime(holder.itemView.getContext(), when,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)
                : holder.itemView.getContext().getString(R.string.backup_unknown_time));
        holder.detail.setText(file.name);
        holder.size.setText(formatSize(holder.itemView.getContext(), file.sizeBytes));
        holder.itemView.setOnClickListener(v -> listener.onBackupClick(file));
    }

    @NonNull
    static String formatSize(@NonNull android.content.Context context, long bytes) {
        if (bytes <= 0) {
            return context.getString(R.string.backup_size_unknown);
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView detail;
        final TextView size;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.backup_row_title);
            detail = itemView.findViewById(R.id.backup_row_detail);
            size = itemView.findViewById(R.id.backup_row_size);
        }
    }
}
