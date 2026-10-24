package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 * A reporting period as a half-open range {@code [start, endExclusive)} of instants. Both bounds are
 * the first moment of a local day, so the last selected day is included in full.
 */
public final class ReportRange implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull public final ReportPreset preset;
    public final long start;
    public final long endExclusive;

    public ReportRange(@NonNull ReportPreset preset, long start, long endExclusive) {
        if (endExclusive <= start) {
            throw new IllegalArgumentException("Empty or inverted range");
        }
        this.preset = preset;
        this.start = start;
        this.endExclusive = endExclusive;
    }

    /** Last instant inside the range, useful for showing the final day. */
    public long lastInstant() {
        return endExclusive - 1;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ReportRange)) {
            return false;
        }
        ReportRange other = (ReportRange) o;
        return preset == other.preset && start == other.start && endExclusive == other.endExclusive;
    }

    @Override
    public int hashCode() {
        return (int) (31 * (31 * preset.hashCode() + Long.hashCode(start)) + Long.hashCode(endExclusive));
    }
}
