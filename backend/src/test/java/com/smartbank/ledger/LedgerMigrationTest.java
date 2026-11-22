package com.smartbank.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrades a database that already has pre-ledger transactions (schema V3) to V4
 * and checks that the backfill explains every balance with balanced entries.
 */
@Testcontainers
class LedgerMigrationTest {
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void backfillReconcilesExistingTransactionsAndSeedBalances() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("3").load().migrate();

        // History written by the pre-ledger application: balances and rows only.
        preLedgerTransaction(jdbc, "DEPOSIT", null, A, "100.00", "2026-01-01T10:00:00Z");
        preLedgerTransaction(jdbc, "TRANSFER", A, B, "50.00", "2026-01-02T10:00:00Z");
        preLedgerTransaction(jdbc, "WITHDRAWAL", B, null, "20.00", "2026-01-03T10:00:00Z");
        jdbc.update("UPDATE accounts SET balance = 1300.00 WHERE id = ?", A);  // 1250 + 100 - 50
        jdbc.update("UPDATE accounts SET balance = 3830.50 WHERE id = ?", B);  // 3800.50 + 50 - 20

        Flyway.configure().dataSource(dataSource).load().migrate();

        var report = new LedgerReconciliation(jdbc).run();
        assertTrue(report.isClean(), () -> "Ledger out of balance after migration: " + report);
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class),
                "Two entries for each of the 3 transactions, the 2 backfilled opening balances, "
                        + "and the USD demo account's opening balance added in V15");
        assertMoney("1250.00", opening(jdbc, A));
        assertMoney("3800.50", opening(jdbc, B));
        assertTrue(jdbc.queryForObject("""
                SELECT o.created_at < (SELECT MIN(created_at) FROM banking_transactions WHERE transaction_type <> 'OPENING_BALANCE')
                FROM banking_transactions o WHERE o.transaction_type = 'OPENING_BALANCE' AND o.destination_account_id = ?""",
                Boolean.class, A), "Opening balance must predate the account's history");
    }

    private static void preLedgerTransaction(JdbcTemplate jdbc, String type, UUID from, UUID to,
                                             String amount, String at) {
        jdbc.update("""
                INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id, amount, currency, created_at)
                VALUES (?, ?, ?, ?, ?, 'CAD', ?::timestamptz)""",
                UUID.randomUUID(), type, from, to, new BigDecimal(amount), at);
    }

    private static BigDecimal opening(JdbcTemplate jdbc, UUID account) {
        return jdbc.queryForObject(
                "SELECT amount FROM banking_transactions WHERE transaction_type = 'OPENING_BALANCE' AND destination_account_id = ?",
                BigDecimal.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
