package com.smartbank.support;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Test data that respects the ledger: whenever a test sets up a balance, it also
 * posts a balanced OPENING_BALANCE transaction for it. Each posting is a single
 * SQL statement, so the deferred balance check passes at its implicit commit.
 */
public final class LedgerTestData {
    private LedgerTestData() { }

    private static final String POST_OPENING_BALANCES = """
            WITH o AS MATERIALIZED (
                SELECT id AS account_id, gen_random_uuid() AS tx, balance, currency
                FROM accounts WHERE balance > 0 AND %s
            ), t AS (
                INSERT INTO banking_transactions
                    (id, transaction_type, source_account_id, destination_account_id, amount, currency, request_key, created_at)
                SELECT tx, 'OPENING_BALANCE', NULL, account_id, balance, currency, NULL, now() - INTERVAL '1 day' FROM o
                RETURNING id
            )
            INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
            SELECT gen_random_uuid(), tx, 'CUSTOMER', account_id, balance, currency, now() - INTERVAL '1 day' FROM o
            UNION ALL
            SELECT gen_random_uuid(), tx, 'OPENING_EQUITY', NULL, -balance, currency, now() - INTERVAL '1 day' FROM o""";

    /**
     * Empties outbox events and deliveries, holds, the ledger and the transaction
     * log, and clears pending amounts.
     * TRUNCATE bypasses the append-only and hold-transition triggers.
     */
    public static void truncateLedger(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE webhook_deliveries, outbox_events, holds, ledger_entries, banking_transactions");
        jdbc.update("UPDATE accounts SET pending_debits = 0, pending_credits = 0");
    }

    /** Posts an opening balance for every account's current balance. Use after {@link #truncateLedger}. */
    public static void postOpeningBalancesForAllAccounts(JdbcTemplate jdbc) {
        jdbc.update(POST_OPENING_BALANCES.formatted("TRUE"));
    }

    /** Creates an account and posts its opening balance. */
    public static UUID openAccount(JdbcTemplate jdbc, String accountNumber, BigDecimal balance, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, account_number, owner_name, account_type, balance, currency)
                VALUES (?, ?, 'Test Customer', 'CHECKING', ?, ?)""", id, accountNumber, balance, currency);
        jdbc.update(POST_OPENING_BALANCES.formatted("id = ?"), id);
        return id;
    }

    /** Transactions created by the API, excluding opening balances set up by tests. */
    public static long postedTransactionCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking_transactions WHERE transaction_type <> 'OPENING_BALANCE'", Long.class);
    }
}
