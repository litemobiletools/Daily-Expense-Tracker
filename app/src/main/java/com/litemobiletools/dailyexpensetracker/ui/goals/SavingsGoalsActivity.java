package com.litemobiletools.dailyexpensetracker.ui.goals;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.InvalidationTracker;

import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.entity.GoalWithSaved;
import com.litemobiletools.dailyexpensetracker.data.repository.GoalRepository;
import com.litemobiletools.dailyexpensetracker.ui.common.SecondaryActivity;

import java.util.List;
import java.util.Set;

/** Lists savings goals (tracking records only). Tapping one opens its details; the button adds a goal. */
public class SavingsGoalsActivity extends SecondaryActivity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private InvalidationTracker.Observer observer;
    private int generation;

    private LinearLayout container;
    private View emptyView;
    private View errorView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initScreen(savedInstanceState, R.layout.activity_goals, R.string.goals_title);
        container = findViewById(R.id.goals_container);
        emptyView = findViewById(R.id.goals_empty);
        errorView = findViewById(R.id.goals_error);
        findViewById(R.id.goals_add_fab).setOnClickListener(v ->
                GoalEditorSheet.show(getSupportFragmentManager(), -1));
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
        observer = new InvalidationTracker.Observer("savings_goals", "goal_contributions") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                mainHandler.post(() -> {
                    if (!isDestroyed()) {
                        load();
                    }
                });
            }
        };
        AppDatabase.getInstance(this).getInvalidationTracker().addObserver(observer);
    }

    @Override
    protected void onStop() {
        if (observer != null) {
            AppDatabase.getInstance(this).getInvalidationTracker().removeObserver(observer);
            observer = null;
        }
        super.onStop();
    }

    private void load() {
        final int gen = ++generation;
        GoalRepository.get(this).list(new RepoCallback<List<GoalWithSaved>>() {
            @Override
            public void onSuccess(List<GoalWithSaved> goals) {
                if (isDestroyed() || gen != generation) {
                    return;
                }
                errorView.setVisibility(View.GONE);
                container.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(SavingsGoalsActivity.this);
                for (GoalWithSaved goal : goals) {
                    View card = inflater.inflate(R.layout.item_progress_card, container, false);
                    GoalRowBinder.bind(card, goal);
                    final long id = goal.goal.id;
                    card.findViewById(R.id.progress_root).setOnClickListener(v ->
                            GoalDetailsSheet.show(getSupportFragmentManager(), id));
                    container.addView(card);
                }
                emptyView.setVisibility(goals.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (!isDestroyed() && gen == generation) {
                    errorView.setVisibility(View.VISIBLE);
                }
            }
        });
    }
}
