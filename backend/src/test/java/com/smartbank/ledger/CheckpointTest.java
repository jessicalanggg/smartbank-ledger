package com.smartbank.ledger;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Balance checkpoints, balances at any moment, and statements, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = "smartbank.ledger.checkpoint-interval=PT1H")
@AutoConfigureMockMvc
class CheckpointTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired BalanceCheckpoints checkpoints;
    @Autowired ChartOfAccounts chart;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired BankingService banking;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private UUID a, b, idle;

    @BeforeEach
    void freshBooks() {
        LedgerTestData.truncateLedger(jdbc);
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        a = account("100.00");
        b = account("50.00");
        idle = account("10.00");
    }

    private UUID account(String balance) {
        return LedgerTestData.openAccount(jdbc, "CKPT-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(balance), "CAD");
    }

    private static String key() { return "ckpt-" + UUID.randomUUID(); }

    /** The current moment, with a pause either side so it falls strictly between postings. */
    private static Instant mark() throws InterruptedException {
        Thread.sleep(15);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Thread.sleep(15);
        return now;
    }

    /** The balance by summing every entry dated before the moment: the slow, obviously right way. */
    private BigDecimal fullScan(UUID account, Instant at) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ? AND created_at < ?",
                BigDecimal.class, account, Timestamp.from(at));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void balanceAtAnyMomentMatchesAFullScan() throws Exception {
        List<Instant> moments = new ArrayList<>();
        moments.add(mark());
        banking.deposit(a, new MoneyRequest(new BigDecimal("25.00"), key()));
        moments.add(mark());
        banking.transfer(new TransferRequest(a, b, new BigDecimal("40.00"), key()));
        Instant first = mark();
        moments.add(first);
        banking.withdraw(b, new MoneyRequest(new BigDecimal("15.00"), key()));
        banking.chargeFee(a, new MoneyRequest(new BigDecimal("1.00"), key()));
        Instant second = mark();
        moments.add(second);
        banking.deposit(a, new MoneyRequest(new BigDecimal("7.00"), key()));
        moments.add(mark());

        assertTrue(checkpoints.checkpointAt(first) > 0);
        assertTrue(checkpoints.checkpointAt(second) > 0);

        for (Instant t : moments)
            for (UUID account : List.of(a, b, idle))
                assertEquals(0, fullScan(account, t).compareTo(checkpoints.customerBalanceAt(account, t)),
                        "balance of " + account + " at " + t);
        assertMoney("91.00", checkpoints.customerBalanceAt(a, Instant.now().plusSeconds(1)));  // 100 + 25 - 40 - 1 + 7

        // Checkpoints are sparse: an account with no activity since its last one gets none.
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_checkpoints c JOIN ledger_accounts la ON la.id = c.ledger_account_id
                WHERE la.customer_account_id = ?""", Integer.class, idle));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_checkpoints c JOIN ledger_accounts la ON la.id = c.ledger_account_id
                WHERE la.customer_account_id = ?""", Integer.class, a));
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void trialBalanceThroughCheckpointsMatchesTheEntries() throws Exception {
        banking.deposit(a, new MoneyRequest(new BigDecimal("30.00"), key()));
        Instant cut = mark();
        checkpoints.checkpointAt(cut);
        banking.transfer(new TransferRequest(a, b, new BigDecimal("12.00"), key()));
        banking.chargeFee(b, new MoneyRequest(new BigDecimal("2.00"), key()));
        Instant later = mark();

        for (Instant t : List.of(cut, later)) {
            var tb = chart.trialBalance("CAD", t);
            assertTrue(tb.balanced() && tb.accountingEquationHolds());
            for (var account : tb.accounts()) {
                if (account.customerAccountId() == null) continue;
                assertEquals(0, fullScan(account.customerAccountId(), t).compareTo(account.balance()), account.code());
            }
        }
    }

    @Test
    void theJobCheckpointsEachBoundaryOnceAndOnlyPastTheHorizon() {
        Instant now = Instant.now();
        Instant today = now.minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.DAYS);
        List<Instant> first = checkpoints.checkpointDue(now);
        assertEquals(List.of(today), first, "Only the latest boundary older than the 10 minute horizon");
        assertTrue(checkpoints.checkpointDue(now).isEmpty(), "Nothing to do again");
        // Three days on, the three boundaries in between are taken, oldest first.
        List<Instant> later = checkpoints.checkpointDue(now.plus(Duration.ofDays(3)));
        assertEquals(List.of(today.plus(Duration.ofDays(1)), today.plus(Duration.ofDays(2)), today.plus(Duration.ofDays(3))), later);
        LedgerTestData.truncateLedger(jdbc);  // those boundaries are in the future; clear them for other tests
    }

    @Test
    void anEntryCommittedAfterItsCheckpointIsReported() throws Exception {
        Instant cut = mark();
        checkpoints.checkpointAt(cut);
        assertEquals(0, reconciliation.run().inconsistentCheckpoints());

        // A transaction dated before the checkpoint but committed after it: what the
        // horizon and transaction timeout exist to prevent.
        UUID tx = UUID.randomUUID();
        Timestamp before = Timestamp.from(cut.minusSeconds(60));
        jdbc.update("""
                WITH t AS (INSERT INTO banking_transactions (id, transaction_type, destination_account_id, amount, currency, created_at)
                           VALUES (?, 'DEPOSIT', ?, 5.00, 'CAD', ?))
                INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                VALUES (gen_random_uuid(), ?, 'CUSTOMER', ?, 5.00, 'CAD', ?),
                       (gen_random_uuid(), ?, 'EXTERNAL_CLEARING', NULL, -5.00, 'CAD', ?)""",
                tx, a, before, tx, a, before, tx, before);
        jdbc.update("UPDATE accounts SET balance = balance + 5 WHERE id = ?", a);
        jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, account_ids, payload, created_at) "
                + "VALUES (gen_random_uuid(), 'transaction.posted', ?, ARRAY[?]::uuid[], '{}', now())", tx, a);
        checkpoints.checkpointAt(cut.plusSeconds(1));  // the next checkpoint sees it; the earlier one cannot

        var report = reconciliation.run();
        assertTrue(report.inconsistentCheckpoints() >= 1, () -> "Late entry not reported: " + report);
        LedgerTestData.truncateLedger(jdbc);
    }

    @Test
    void anAlteredCheckpointIsReported() throws Exception {
        banking.deposit(a, new MoneyRequest(new BigDecimal("1.00"), key()));
        checkpoints.checkpointAt(mark());
        jdbc.execute("ALTER TABLE ledger_checkpoints DISABLE TRIGGER USER");
        try {
            jdbc.update("""
                    UPDATE ledger_checkpoints SET credits = credits + 1000
                    WHERE ledger_account_id = (SELECT id FROM ledger_accounts WHERE customer_account_id = ?)""", a);
        } finally {
            jdbc.execute("ALTER TABLE ledger_checkpoints ENABLE TRIGGER USER");
        }
        assertEquals(1, reconciliation.run().inconsistentCheckpoints());
        LedgerTestData.truncateLedger(jdbc);
    }

    @Test
    void statementShowsOpeningLinesWithRunningBalanceAndClosing() throws Exception {
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER'), (?, ?, 'x', 'CUSTOMER')",
                owner, owner + "@t.local", stranger, stranger + "@t.local");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, a);
        banking.deposit(a, new MoneyRequest(new BigDecimal("20.00"), key()));
        Instant from = mark();
        checkpoints.checkpointAt(from);
        banking.transfer(new TransferRequest(a, b, new BigDecimal("45.00"), key()));
        banking.deposit(a, new MoneyRequest(new BigDecimal("5.00"), key()));
        Instant to = mark();
        banking.deposit(a, new MoneyRequest(new BigDecimal("1000.00"), key()));  // after the period

        String json = mvc.perform(get("/api/v1/accounts/{id}/statement", a)
                        .param("from", from.toString()).param("to", to.toString())
                        .with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(120.00, ((Number) JsonPath.read(json, "$.openingBalance")).doubleValue());
        assertEquals(80.00, ((Number) JsonPath.read(json, "$.closingBalance")).doubleValue());
        assertEquals(5.00, ((Number) JsonPath.read(json, "$.moneyIn")).doubleValue());
        assertEquals(45.00, ((Number) JsonPath.read(json, "$.moneyOut")).doubleValue());
        assertEquals(List.of(-45.0, 5.0), ((List<?>) JsonPath.read(json, "$.lines[*].amount")).stream()
                .map(n -> ((Number) n).doubleValue()).toList());
        assertEquals(List.of(75.0, 80.0), ((List<?>) JsonPath.read(json, "$.lines[*].balance")).stream()
                .map(n -> ((Number) n).doubleValue()).toList());
        String bNumber = jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class, b);
        assertEquals(bNumber, JsonPath.read(json, "$.lines[0].counterparty"));
        assertEquals("TRANSFER", JsonPath.read(json, "$.lines[0].type"));
        assertEquals(false, JsonPath.read(json, "$.truncated"));

        mvc.perform(get("/api/v1/accounts/{id}/statement", a).with(jwt().jwt(j -> j.subject(stranger.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/{id}/statement", a).param("from", to.toString()).param("to", from.toString())
                .with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/{id}/statement", a).param("from", from.minus(Duration.ofDays(400)).toString())
                .param("to", from.toString()).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isBadRequest());
    }

    @Test
    void transactionsAreTimeLimited() {
        assertEquals("2min", jdbc.queryForObject("SHOW transaction_timeout", String.class));
    }
}
