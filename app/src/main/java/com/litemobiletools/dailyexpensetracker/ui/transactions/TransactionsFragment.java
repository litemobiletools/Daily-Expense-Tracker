package com.litemobiletools.dailyexpensetracker.ui.transactions;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.InvalidationTracker;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.litemobiletools.dailyexpensetracker.R;
import com.litemobiletools.dailyexpensetracker.settings.AppPreferences;
import com.litemobiletools.dailyexpensetracker.data.AppDatabase;
import com.litemobiletools.dailyexpensetracker.data.HistoryPage;
import com.litemobiletools.dailyexpensetracker.data.RepoCallback;
import com.litemobiletools.dailyexpensetracker.data.TransactionFilter;
import com.litemobiletools.dailyexpensetracker.data.entity.TransactionRow;
import com.litemobiletools.dailyexpensetracker.data.repository.TransactionRepository;
import com.litemobiletools.dailyexpensetracker.ui.add.AddTransactionActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Full transaction history: database-backed search, filters, date grouping and paging.
 * Only one page of rows is held in memory at a time per scroll position; more load as the user scrolls.
 */
public class TransactionsFragment extends Fragment {

    private static final int PAGE_SIZE = 50;
    private static final long SEARCH_DEBOUNCE_MS = 300;
    private static final int LOAD_MORE_THRESHOLD = 12;
    private static final String STATE_FILTER = "filter";
    private static final String ARG_INITIAL_FILTER = "initial_filter";

    /** @param initialFilter filter to start with (for example a report's period), or null for none */
    @NonNull
    public static TransactionsFragment newInstance(@Nullable TransactionFilter initialFilter) {
        TransactionsFragment fragment = new TransactionsFragment();
        if (initialFilter != null) {
            Bundle args = new Bundle();
            args.putSerializable(ARG_INITIAL_FILTER, initialFilter);
            fragment.setArguments(args);
        }
        return fragment;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::applySearchText;
    private InvalidationTracker.Observer observer;

    private TransactionFilter filter = new TransactionFilter();
    private final List<TransactionRow> rows = new ArrayList<>();
    private int totalCount;
    private boolean loadedOnce;
    private boolean loading;
    private boolean endReached;
    private boolean failed;
    /** Incremented by every reload so late results of an older query are ignored. */
    private int generation;

    private HistoryAdapter adapter;
    private TransactionRepository repository;
    private TextInputEditText searchInput;
    private MaterialButton filterButton;
    private MaterialButton clearFilters;
    private TextView countView;
    private RecyclerView list;
    private View loadingView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private MaterialButton emptyAction;
    private View errorView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transactions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = TransactionRepository.get(requireContext());
        if (savedInstanceState != null) {
            TransactionFilter saved = (TransactionFilter) savedInstanceState.getSerializable(STATE_FILTER);
            if (saved != null) {
                filter = saved;
            }
        } else if (getArguments() != null) {
            TransactionFilter initial = (TransactionFilter) getArguments().getSerializable(ARG_INITIAL_FILTER);
            if (initial != null) {
                filter = initial.copy();
            }
        }

        searchInput = view.findViewById(R.id.search_input);
        filterButton = view.findViewById(R.id.filter_button);
        clearFilters = view.findViewById(R.id.clear_filters);
        countView = view.findViewById(R.id.history_count);
        list = view.findViewById(R.id.history_list);
        loadingView = view.findViewById(R.id.history_loading);
        emptyView = view.findViewById(R.id.history_empty);
        emptyTitle = view.findViewById(R.id.history_empty_title);
        emptyMessage = view.findViewById(R.id.history_empty_message);
        emptyAction = view.findViewById(R.id.history_empty_action);
        errorView = view.findViewById(R.id.history_error);

        adapter = new HistoryAdapter(row ->
                TransactionDetailsSheet.show(getParentFragmentManager(), row.id));
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (dy > 0 && manager != null && manager.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - LOAD_MORE_THRESHOLD) {
                    loadMore();
                }
            }
        });

        searchInput.setText(filter.searchText);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Wait for a pause in typing so each keystroke does not start a query.
                mainHandler.removeCallbacks(searchRunnable);
                mainHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                mainHandler.removeCallbacks(searchRunnable);
                applySearchText();
                return true;
            }
            return false;
        });

        filterButton.setOnClickListener(v ->
                FilterBottomSheet.show(getChildFragmentManager(), currentFilter()));
        getChildFragmentManager().setFragmentResultListener(FilterBottomSheet.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    TransactionFilter updated = (TransactionFilter) result.getSerializable(
                            FilterBottomSheet.RESULT_FILTER);
                    if (updated != null) {
                        filter = updated;
                        reload(false);
                    }
                });
        clearFilters.setOnClickListener(v -> {
            String keepSearch = filter.searchText;
            filter = new TransactionFilter();
            filter.searchText = keepSearch;
            reload(false);
        });
        emptyAction.setOnClickListener(v -> {
            filter = new TransactionFilter();
            searchInput.setText("");
            reload(false);
        });
        view.findViewById(R.id.history_retry).setOnClickListener(v -> reload(false));
        view.findViewById(R.id.transactions_add_fab).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddTransactionActivity.class)));

        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        reload(true);
        observer = new InvalidationTracker.Observer("transactions", "categories", "payment_methods") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                // Room background thread: hop to main, then re-run the query keeping the loaded size.
                mainHandler.post(() -> {
                    if (isAdded() && getView() != null) {
                        reload(true);
                    }
                });
            }
        };
        AppDatabase.getInstance(requireContext()).getInvalidationTracker().addObserver(observer);
    }

    @Override
    public void onStop() {
        if (observer != null) {
            AppDatabase.getInstance(requireContext()).getInvalidationTracker().removeObserver(observer);
            observer = null;
        }
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(STATE_FILTER, currentFilter());
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacks(searchRunnable);
        generation++; // Drop any result still on its way to the destroyed views.
        list.setAdapter(null);
        adapter = null;
        searchInput = null;
        filterButton = null;
        clearFilters = null;
        countView = null;
        list = null;
        loadingView = null;
        emptyView = null;
        emptyTitle = null;
        emptyMessage = null;
        emptyAction = null;
        errorView = null;
        super.onDestroyView();
    }

    // ---- Querying --------------------------------------------------------------------

    private TransactionFilter currentFilter() {
        TransactionFilter copy = filter.copy();
        if (searchInput != null && searchInput.getText() != null) {
            copy.searchText = searchInput.getText().toString();
        }
        return copy;
    }

    private void applySearchText() {
        if (searchInput == null || searchInput.getText() == null) {
            return;
        }
        String text = searchInput.getText().toString();
        if (text.equals(filter.searchText)) {
            return; // Nothing changed (for example only the debounce fired twice): no new query.
        }
        filter.searchText = text;
        reload(false);
    }

    /**
     * @param keepSize true to refresh in place (reload as many rows as are loaded now, so the scroll
     *                 position survives data changes); false to start again from the first page
     */
    private void reload(boolean keepSize) {
        final int gen = ++generation;
        final int limit = keepSize ? Math.max(PAGE_SIZE, rows.size()) : PAGE_SIZE;
        loading = true;
        failed = false;
        if (!keepSize) {
            rows.clear();
            loadedOnce = false;
        }
        render();
        repository.loadHistory(filter, AppPreferences.getCurrencyCode(requireContext()), limit, 0, true,
                new RepoCallback<HistoryPage>() {
                    @Override
                    public void onSuccess(HistoryPage page) {
                        if (gen != generation || getView() == null) {
                            return;
                        }
                        rows.clear();
                        rows.addAll(page.rows);
                        totalCount = page.totalCount;
                        endReached = page.rows.size() < limit;
                        loading = false;
                        loadedOnce = true;
                        render();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        if (gen != generation || getView() == null) {
                            return;
                        }
                        loading = false;
                        failed = true;
                        render();
                    }
                });
    }

    private void loadMore() {
        if (loading || endReached || failed || rows.isEmpty()) {
            return;
        }
        final int gen = generation;
        loading = true;
        repository.loadHistory(filter, AppPreferences.getCurrencyCode(requireContext()), PAGE_SIZE, rows.size(), false,
                new RepoCallback<HistoryPage>() {
                    @Override
                    public void onSuccess(HistoryPage page) {
                        if (gen != generation || getView() == null) {
                            return;
                        }
                        rows.addAll(page.rows);
                        endReached = page.rows.size() < PAGE_SIZE;
                        loading = false;
                        render();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        if (gen == generation) {
                            loading = false; // Scrolling again retries.
                        }
                    }
                });
    }

    // ---- Rendering -------------------------------------------------------------------

    private void render() {
        if (getView() == null || adapter == null) {
            return;
        }
        int active = filter.activeCount();
        boolean criteria = filter.hasCriteria() || (searchInput != null && searchInput.getText() != null
                && !searchInput.getText().toString().trim().isEmpty());

        filterButton.setText(active > 0
                ? getString(R.string.filters_button_count, active) : getString(R.string.filters_button));
        filterButton.setContentDescription(getString(R.string.filters_button_description, active));
        clearFilters.setVisibility(active > 0 ? View.VISIBLE : View.GONE);

        boolean showError = failed && rows.isEmpty();
        boolean showLoading = !loadedOnce && !failed;
        boolean showEmpty = loadedOnce && !failed && rows.isEmpty();

        if (loadedOnce || showError) {
            countView.setText(showError ? "" : getResources().getQuantityString(
                    criteria ? R.plurals.transactions_count_filtered : R.plurals.transactions_count,
                    totalCount, totalCount));
        } else {
            countView.setText("");
        }

        loadingView.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        errorView.setVisibility(showError ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(rows.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (showEmpty) {
            emptyTitle.setText(criteria ? R.string.history_noresults_title : R.string.history_empty_title);
            emptyMessage.setText(criteria ? R.string.history_noresults_message : R.string.history_empty_message);
            emptyAction.setVisibility(criteria ? View.VISIBLE : View.GONE);
        }

        adapter.submitList(HistoryGrouper.group(rows, TimeZone.getDefault()));
    }
}
