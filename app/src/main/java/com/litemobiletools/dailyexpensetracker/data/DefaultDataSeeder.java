package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.litemobiletools.dailyexpensetracker.data.entity.CategoryEntity;
import com.litemobiletools.dailyexpensetracker.data.entity.PaymentMethodEntity;
import com.litemobiletools.dailyexpensetracker.model.TransactionType;

/**
 * Inserts the built-in categories and payment methods. Safe to call on every launch: each row
 * has a unique {@code default_key}, inserts ignore existing keys, and the whole check is
 * skipped once everything is present. User renames or deactivations are never overwritten.
 */
public final class DefaultDataSeeder {

    // key, name, icon, color (ARGB)
    private static final Object[][] EXPENSE = {
            {"expense_food", "Food", "restaurant", 0xFF3E7CB1},
            {"expense_transport", "Transport", "directions_car", 0xFF1B9E6B},
            {"expense_shopping", "Shopping", "shopping_bag", 0xFFD9A21B},
            {"expense_bills", "Bills", "receipt_long", 0xFFC0576B},
            {"expense_health", "Health", "favorite", 0xFF7B6CC4},
            {"expense_education", "Education", "school", 0xFF2E8BA6},
            {"expense_entertainment", "Entertainment", "movie", 0xFFB5651D},
            {"expense_other", "Other", "category", 0xFF6B7785},
    };

    private static final Object[][] INCOME = {
            {"income_salary", "Salary", "payments", 0xFF1B9E6B},
            {"income_business", "Business", "storefront", 0xFF3E7CB1},
            {"income_freelance", "Freelance", "laptop", 0xFF7B6CC4},
            {"income_investment", "Investment", "trending_up", 0xFFD9A21B},
            {"income_gift", "Gift", "card_giftcard", 0xFFC0576B},
            {"income_other", "Other", "category", 0xFF6B7785},
    };

    private static final Object[][] PAYMENT_METHODS = {
            {"cash", "Cash", "payments"},
            {"bank_transfer", "Bank Transfer", "account_balance"},
            {"debit_card", "Debit Card", "credit_card"},
            {"credit_card", "Credit Card", "credit_card"},
            {"mobile_wallet", "Mobile Wallet", "smartphone"},
            {"other", "Other", "category"},
    };

    private DefaultDataSeeder() {
    }

    public static int defaultCategoryCount() {
        return EXPENSE.length + INCOME.length;
    }

    public static int defaultPaymentMethodCount() {
        return PAYMENT_METHODS.length;
    }

    @WorkerThread
    public static void seed(@NonNull AppDatabase db) {
        db.runInTransaction(() -> {
            if (db.categoryDao().countDefaults() < defaultCategoryCount()) {
                insertCategories(db, EXPENSE, TransactionType.EXPENSE);
                insertCategories(db, INCOME, TransactionType.INCOME);
            }
            if (db.paymentMethodDao().countDefaults() < defaultPaymentMethodCount()) {
                int order = 0;
                for (Object[] row : PAYMENT_METHODS) {
                    PaymentMethodEntity method = new PaymentMethodEntity();
                    method.defaultKey = (String) row[0];
                    method.name = (String) row[1];
                    method.iconKey = (String) row[2];
                    method.sortOrder = order++;
                    db.paymentMethodDao().insertIgnore(method);
                }
            }
        });
    }

    private static void insertCategories(AppDatabase db, Object[][] rows, TransactionType type) {
        long now = System.currentTimeMillis();
        int order = 0;
        for (Object[] row : rows) {
            CategoryEntity category = new CategoryEntity();
            category.defaultKey = (String) row[0];
            category.name = (String) row[1];
            category.iconKey = (String) row[2];
            category.colorArgb = (Integer) row[3];
            category.type = type;
            category.sortOrder = order++;
            category.createdAt = now;
            category.updatedAt = now;
            db.categoryDao().insertIgnore(category);
        }
    }
}
