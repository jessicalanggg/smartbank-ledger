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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tamper evidence. The tests change sealed data the way someone with direct database
 * access could: they switch off the append-only triggers first. The background
 * sealer is slowed to once an hour; tests seal explicitly.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = "smartbank.ledger.seal-interval=PT1H")
@AutoConfigureMockMvc
class LedgerSealTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired LedgerSealer sealer;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired BankingService banking;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private UUID a, b;

    @BeforeEach
    void freshBooks() {
        LedgerTestData.truncateLedger(jdbc);
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        a = LedgerTestData.openAccount(jdbc, "SEAL-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "CAD");
        b = LedgerTestData.openAccount(jdbc, "SEAL-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "CAD");
    }

    private UUID transfer(String amount) {
        return banking.transfer(new TransferRequest(a, b, new BigDecimal(amount), "seal-" + UUID.randomUUID())).id();
    }

    /** Runs SQL as someone with direct database access: append-only triggers off, then back on. */
    private void tamper(String... statements) {
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE banking_transactions DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE ledger_seals DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE ledger_sealed_transactions DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE outbox_events DISABLE TRIGGER USER");
        try {
            for (String sql : statements) jdbc.execute(sql);
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE banking_transactions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE ledger_seals ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE ledger_sealed_transactions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE outbox_events ENABLE TRIGGER USER");
        }
    }

    @Test
    void everyCommittedTransactionIsSealedIntoOneChain() {
        transfer("10.00");
        var first = sealer.sealPending();
        transfer("5.00");
        transfer("1.00");
        var second = sealer.sealPending();

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(LedgerSealer.GENESIS, first.get(0).prevHash());
        assertEquals(first.get(0).hash(), second.get(0).prevHash(), "Each seal links to the one before");
        assertEquals(2, second.get(0).transactionCount());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking_transactions t
                WHERE NOT EXISTS (SELECT 1 FROM ledger_sealed_transactions s WHERE s.transaction_id = t.id)""", Integer.class));
        var check = sealer.verify();
        assertTrue(check.intact());
        assertEquals(2, check.sealsChecked());
        assertEquals(second.get(0).hash(), check.headHash());
        assertTrue(sealer.sealPending().isEmpty(), "Nothing new to seal");
    }

    @Test
    void aBalancedChangeThatReconciliationCannotSeeIsCaughtByTheChain() {
        UUID tx = transfer("30.00");
        sealer.sealPending();
        // Quietly turn a 30.00 transfer into 40.00, keeping every other check consistent:
        // the transaction, both entries and both cached balances all change together.
        tamper("UPDATE banking_transactions SET amount = 40.00 WHERE id = '" + tx + "'",
               "UPDATE ledger_entries SET amount = amount * 40 / 30 WHERE transaction_id = '" + tx + "'",
               "UPDATE accounts SET balance = balance - 10 WHERE id = '" + a + "'",
               "UPDATE accounts SET balance = balance + 10 WHERE id = '" + b + "'");

        var report = reconciliation.run();
        assertEquals(0, report.accountsOutOfBalance(), "Balances still match the (altered) entries");
        assertEquals(0, report.unbalancedTransactions(), "Debits still equal credits");
        assertEquals(1, report.brokenSeals(), "Only the hash chain notices");
        var check = sealer.verify();
        assertEquals(1L, check.firstBrokenSeal());
        assertEquals("sealed transactions or entries were changed", check.problem());
    }

    @Test
    void changedTransactionDetailsAreCaught() {
        UUID c = LedgerTestData.openAccount(jdbc, "SEAL-" + UUID.randomUUID().toString().substring(0, 8), BigDecimal.ZERO, "CAD");
        UUID tx = transfer("10.00");
        sealer.sealPending();
        tamper("UPDATE banking_transactions SET destination_account_id = '" + c + "' WHERE id = '" + tx + "'");
        assertFalse(sealer.verify().intact());
    }

    @Test
    void deletedTransactionsAreCaught() {
        transfer("1.00");
        UUID gone = transfer("2.00");
        sealer.sealPending();
        tamper("DELETE FROM outbox_events WHERE aggregate_id = '" + gone + "'",
               "DELETE FROM ledger_sealed_transactions WHERE transaction_id = '" + gone + "'",
               "DELETE FROM ledger_entries WHERE transaction_id = '" + gone + "'",
               "DELETE FROM banking_transactions WHERE id = '" + gone + "'");
        var check = sealer.verify();
        assertFalse(check.intact());
        assertEquals("sealed transactions were added or removed", check.problem());
    }

    @Test
    void reSealingTheTamperedSealBreaksTheNextLink() {
        UUID tx = transfer("10.00");
        sealer.sealPending();
        transfer("1.00");
        sealer.sealPending();
        String recordedHead = sealer.head().hash();  // kept outside the database by an auditor

        // A careful attacker changes a transaction and recomputes seal 1 to match.
        tamper("UPDATE banking_transactions SET request_key = 'forged' WHERE id = '" + tx + "'");
        List<UUID> ids = jdbc.queryForList("SELECT transaction_id FROM ledger_sealed_transactions s "
                + "JOIN banking_transactions t ON t.id = s.transaction_id WHERE seal_seq = 1 ORDER BY t.created_at, t.id", UUID.class);
        String forged = LedgerSealer.sealHash(1, LedgerSealer.GENESIS, sealer.canonical(ids));
        tamper("UPDATE ledger_seals SET hash = '" + forged + "' WHERE seq = 1");
        var check = sealer.verify();
        assertEquals(2L, check.firstBrokenSeal());
        assertEquals("does not link to the previous seal", check.problem());

        // Rewriting every later seal too makes the chain consistent again, but the head
        // no longer matches the copy kept outside the database.
        String prev = forged;
        for (long seq = 2; seq <= sealer.head().seq(); seq++) {
            List<UUID> later = jdbc.queryForList("SELECT transaction_id FROM ledger_sealed_transactions s "
                    + "JOIN banking_transactions t ON t.id = s.transaction_id WHERE seal_seq = ? ORDER BY t.created_at, t.id", UUID.class, seq);
            String h = LedgerSealer.sealHash(seq, prev, sealer.canonical(later));
            tamper("UPDATE ledger_seals SET prev_hash = '" + prev + "', hash = '" + h + "' WHERE seq = " + seq);
            prev = h;
        }
        var rewritten = sealer.verify();
        assertTrue(rewritten.intact(), "A full rewrite is internally consistent");
        assertNotEquals(recordedHead, rewritten.headHash(), "but the head differs from the externally recorded one");
    }

    @Test
    void concurrentSealersNeverForkTheChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 200; i++) work.add(pool.submit(() -> transfer("0.01")));
            for (int i = 0; i < 20; i++) work.add(pool.submit(() -> sealer.sealPending()));
            for (var f : work) f.get(1, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        sealer.sealPending();
        var check = sealer.verify();
        assertTrue(check.intact(), () -> "Chain broken: " + check);
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions", Integer.class), check.transactionsChecked(),
                "Every transaction sealed exactly once");
    }

    @Test
    void transactionsRecordWhoStartedThem() throws Exception {
        UUID user = UUID.randomUUID(), admin = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, 'payer@t.local', 'x', 'CUSTOMER'), (?, ?, 'x', 'ADMIN')",
                user, admin, admin + "@t.local");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", user, a);
        String body = mvc.perform(post("/api/v1/transfers").with(jwt().jwt(j -> j.subject(user.toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"3.00\"}".formatted(a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        UUID system = transfer("1.00");  // no logged-in user: the system
        sealer.sealPending();

        String audit = mvc.perform(get("/api/v1/ledger/transactions/{id}", id).with(jwt().jwt(j -> j.subject(admin.toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(user.toString(), JsonPath.read(audit, "$.initiatedBy"));
        assertEquals("payer@t.local", JsonPath.read(audit, "$.initiatedByEmail"));
        assertNotNull(JsonPath.read(audit, "$.sealSeq"));
        assertNull(jdbc.queryForObject("SELECT initiated_by FROM banking_transactions WHERE id = ?", UUID.class, system));
        mvc.perform(get("/api/v1/ledger/transactions/{id}", id).with(jwt().jwt(j -> j.subject(user.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ledger/seals/verify").with(jwt().jwt(j -> j.subject(admin.toString()))))
                .andExpect(status().isOk());
    }
}
