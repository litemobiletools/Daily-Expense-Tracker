package com.litemobiletools.dailyexpensetracker.data;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Explicit schema migrations. Destructive migration is never used. The SQL below is copied from
 * the schema Room exports to {@code app/schemas}, so it matches what Room validates.
 */
public final class Migrations {

    private Migrations() {
    }

    /**
     * Phase 3 -> Phase 4: adds budgets, savings goals (+ contributions) and recurring rules (+ occurrences).
     * It only creates new tables and indexes; transactions, categories and payment methods are not touched.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `period` TEXT NOT NULL, `category_id` INTEGER, `currency_code` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_period_category_id_currency_code` ON `budgets` (`period`, `category_id`, `currency_code`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_category_id` ON `budgets` (`category_id`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `target_minor` INTEGER NOT NULL, `currency_code` TEXT NOT NULL, `target_date` INTEGER, `is_completed` INTEGER NOT NULL, `completed_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `goal_contributions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goal_id` INTEGER NOT NULL, `delta_minor` INTEGER NOT NULL, `note` TEXT, `contributed_at` INTEGER NOT NULL, FOREIGN KEY(`goal_id`) REFERENCES `savings_goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_contributions_goal_id_contributed_at` ON `goal_contributions` (`goal_id`, `contributed_at`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency_code` TEXT NOT NULL, `category_id` INTEGER NOT NULL, `payment_method_id` INTEGER, `note` TEXT, `frequency` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `end_date` INTEGER, `next_index` INTEGER NOT NULL, `is_active` INTEGER NOT NULL, `skipped_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`payment_method_id`) REFERENCES `payment_methods`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_category_id` ON `recurring_rules` (`category_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_payment_method_id` ON `recurring_rules` (`payment_method_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_is_active` ON `recurring_rules` (`is_active`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `rule_id` INTEGER NOT NULL, `occurrence_date` INTEGER NOT NULL, `transaction_id` INTEGER, `created_at` INTEGER NOT NULL, FOREIGN KEY(`rule_id`) REFERENCES `recurring_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_occurrences_rule_id_occurrence_date` ON `recurring_occurrences` (`rule_id`, `occurrence_date`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_occurrences_transaction_id` ON `recurring_occurrences` (`transaction_id`)");
        }
    };
}
