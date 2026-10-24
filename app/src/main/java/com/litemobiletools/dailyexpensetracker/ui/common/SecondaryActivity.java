package com.litemobiletools.dailyexpensetracker.ui.common;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.litemobiletools.dailyexpensetracker.R;

/**
 * Base for full-screen destinations opened from the Budgets tab: edge-to-edge window, system-bar and
 * cutout insets, and a toolbar with a Back arrow. The layout must have a root with id {@code screen_root}
 * and include {@code layout_app_bar}.
 */
public abstract class SecondaryActivity extends AppCompatActivity {

    protected void initScreen(@Nullable Bundle savedInstanceState, @LayoutRes int layout, @StringRes int title) {
        EdgeToEdge.enable(this);
        setContentView(layout);
        View root = findViewById(R.id.screen_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.navigate_back);
        toolbar.setNavigationOnClickListener(v -> finish());
    }
}
