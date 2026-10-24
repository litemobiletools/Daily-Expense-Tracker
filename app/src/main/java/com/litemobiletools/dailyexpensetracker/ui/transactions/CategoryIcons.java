package com.litemobiletools.dailyexpensetracker.ui.transactions;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.litemobiletools.dailyexpensetracker.R;

/** Maps the icon identifiers stored with categories to bundled drawables. Unknown keys fall back to a generic icon. */
public final class CategoryIcons {

    private CategoryIcons() {
    }

    @DrawableRes
    public static int resFor(@Nullable String iconKey) {
        if (iconKey == null) {
            return R.drawable.ic_cat_other;
        }
        switch (iconKey) {
            case "restaurant":
                return R.drawable.ic_cat_restaurant;
            case "directions_car":
                return R.drawable.ic_cat_car;
            case "shopping_bag":
                return R.drawable.ic_cat_shopping;
            case "receipt_long":
                return R.drawable.ic_receipt;
            case "favorite":
                return R.drawable.ic_cat_favorite;
            case "school":
                return R.drawable.ic_cat_school;
            case "movie":
                return R.drawable.ic_cat_movie;
            case "payments":
                return R.drawable.ic_currency;
            case "storefront":
                return R.drawable.ic_cat_store;
            case "laptop":
                return R.drawable.ic_cat_laptop;
            case "trending_up":
                return R.drawable.ic_cat_trending_up;
            case "card_giftcard":
                return R.drawable.ic_cat_gift;
            default:
                return R.drawable.ic_cat_other;
        }
    }
}
