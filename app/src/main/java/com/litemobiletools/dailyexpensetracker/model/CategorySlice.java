package com.litemobiletools.dailyexpensetracker.model;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;

/** One category's share of spending in the breakdown chart. Amount is in minor currency units. */
public final class CategorySlice {

    @NonNull public final String name;
    public final long amountMinor;
    @ColorRes public final int colorRes;

    public CategorySlice(@NonNull String name, long amountMinor, @ColorRes int colorRes) {
        this.name = name;
        this.amountMinor = amountMinor;
        this.colorRes = colorRes;
    }
}
