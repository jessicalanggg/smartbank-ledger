package com.smartbank.ledger;

import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ledger's guarantees, checked against real PostgreSQL. Tests create their own
 * accounts and never truncate, so the seed data's migrated opening balances stay
 * intact and reconciliation must hold across the whole class.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
class LedgerIntegrationTest {
    private static final UUID SEED_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEED_B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired BankingService banking;
    @Autowired LedgerEntryRepository ledger;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    private UUID newAccount(String balance) {
        return LedgerTestData.openAccount(jdbc, "LEDG-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
    }

    /** Ledger account (and customer account, if any) mapped to the entry amount. */
    private Map<String, BigDecimal> entries(UUID transactionId) {
        return ledger.findByTransactionId(transactionId).stream().collect(Collectors.toMap(
                e -> e.getLedgerAccount() + (e.getAccountId() == null ? "" : ":" + e.getAccountId()),
                LedgerEntry::getAmount));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Test
    void migrationPostedSeedBalancesAsOpeningBalances() {
        assertEquals(0, money("1250.00").compareTo(jdbc.queryForObject(
                "SELECT amount FROM banking_transactions WHERE transaction_type = 'OPENING_BALANCE' AND destination_account_id = ?",
                BigDecimal.class, SEED_A)));
        assertEquals(0, money("3800.50").compareTo(jdbc.queryForObject(
                "SELECT amount FROM banking_transactions WHERE transaction_type = 'OPENING_BALANCE' AND destination_account_id = ?",
                BigDecimal.class, SEED_B)));
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void depositDebitsExternalClearingAndCreditsCustomer() {
        UUID account = newAccount("100.00");
        var tx = banking.deposit(account, new MoneyRequest(money("30.00"), "ledger-deposit-" + account));

        assertEquals(Map.of(
                "EXTERNAL_CLEARING", money("-30.00"),
                "CUSTOMER:" + account, money("30.00")), entries(tx.id()));
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void withdrawalDebitsCustomerAndCreditsExternalClearing() {
        UUID account = newAccount("100.00");
        var tx = banking.withdraw(account, new MoneyRequest(money("30.00"), "ledger-withdraw-" + account));

        assertEquals(Map.of(
                "CUSTOMER:" + account, money("-30.00"),
                "EXTERNAL_CLEARING", money("30.00")), entries(tx.id()));
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void transferMovesMoneyBetweenCustomerAccountsOnly() {
        UUID from = newAccount("100.00"), to = newAccount("0.00");
        var tx = banking.transfer(new TransferRequest(from, to, money("45.50"), "ledger-transfer-" + from));

        assertEquals(Map.of(
                "CUSTOMER:" + from, money("-45.50"),
                "CUSTOMER:" + to, money("45.50")), entries(tx.id()));
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void replayedRequestDoesNotPostAgain() {
        UUID account = newAccount("100.00");
        MoneyRequest request = new MoneyRequest(money("10.00"), "ledger-replay-" + account);
        var first = banking.deposit(account, request);
        banking.deposit(account, request);

        assertEquals(2, ledger.findByTransactionId(first.id()).size());
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ? OR transaction_id IN "
                        + "(SELECT id FROM banking_transactions WHERE ? IN (source_account_id, destination_account_id))",
                Integer.class, account, account),
                "Opening balance and one deposit: two entries each");
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        UUID account = newAccount("100.00");
        var tx = banking.deposit(account, new MoneyRequest(money("5.00"), "ledger-immutable-" + account));

        Exception update = assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE ledger_entries SET amount = amount * 2 WHERE transaction_id = ?", tx.id()));
        assertTrue(rootMessage(update).contains("append-only"), rootMessage(update));
        Exception delete = assertThrows(Exception.class, () -> jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?", tx.id()));
        assertTrue(rootMessage(delete).contains("append-only"), rootMessage(delete));
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE banking_transactions SET amount = 1 WHERE id = ?", tx.id()));
        assertThrows(Exception.class, () -> jdbc.update(
                "DELETE FROM banking_transactions WHERE id = ?", tx.id()));
        assertEquals(2, ledger.findByTransactionId(tx.id()).size());
    }

    @Test
    void unbalancedEntriesAreRejectedAtCommit() {
        UUID account = newAccount("100.00");
        UUID txId = UUID.randomUUID();
        Exception e = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO banking_transactions (id, transaction_type, destination_account_id, amount, currency, created_at)
                    VALUES (?, 'DEPOSIT', ?, 10.00, 'CAD', now())""", txId, account);
            // Credit the customer without the matching debit: money from nowhere.
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                    VALUES (gen_random_uuid(), ?, 'CUSTOMER', ?, 10.00, 'CAD', now())""", txId, account);
        }));
        assertTrue(rootMessage(e).contains("not balanced"), rootMessage(e));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking_transactions WHERE id = ?", Integer.class, txId));
    }

    @Test
    void transactionWithoutEntriesIsRejectedAtCommit() {
        UUID account = newAccount("100.00");
        UUID txId = UUID.randomUUID();
        Exception e = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status ->
                jdbc.update("""
                        INSERT INTO banking_transactions (id, transaction_type, destination_account_id, amount, currency, created_at)
                        VALUES (?, 'DEPOSIT', ?, 10.00, 'CAD', now())""", txId, account)));
        assertTrue(rootMessage(e).contains("not balanced"), rootMessage(e));
    }

    @Test
    void reconciliationDetectsBalanceChangedOutsideTheLedger() {
        UUID account = newAccount("100.00");
        jdbc.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", account);
        try {
            var report = reconciliation.run();
            assertFalse(report.isClean());
            assertEquals(1, report.accountsOutOfBalance());
        } finally {
            jdbc.update("UPDATE accounts SET balance = balance - 1 WHERE id = ?", account);
        }
        assertTrue(reconciliation.run().isClean());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
