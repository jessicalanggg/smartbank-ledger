package com.smartbank.idempotency;

import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Idempotency keys through the secured HTTP API, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired IdempotencyKeyPruner pruner;

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    // ---- Helpers -------------------------------------------------------------

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'not-a-real-hash', 'CUSTOMER')",
                id, id + "@test.local");
        return id;
    }

    private UUID account(UUID owner, String balance) {
        UUID id = LedgerTestData.openAccount(jdbc, "IDEM-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private static RequestPostProcessor as(UUID user) {
        return jwt().jwt(j -> j.subject(user.toString()));
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, UUID user, String key, String body)
            throws Exception {
        if (key != null) request.header(IdempotencyService.HEADER, key);
        return mvc.perform(request.with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse transfer(UUID user, String key, UUID from, UUID to, String amount) throws Exception {
        return send(post("/api/v1/transfers"), user, key,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\"}".formatted(from, to, amount));
    }

    private MockHttpServletResponse withdraw(UUID user, String key, UUID account, String amount) throws Exception {
        return send(post("/api/v1/accounts/{id}/withdrawals", account), user, key, "{\"amount\":\"%s\"}".formatted(amount));
    }

    private MockHttpServletResponse deposit(UUID user, String key, UUID account, String amount) throws Exception {
        return send(post("/api/v1/accounts/{id}/deposits", account), user, key, "{\"amount\":\"%s\"}".formatted(amount));
    }

    private long postingsFrom(UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions WHERE source_account_id = ?", Long.class, account);
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    // ---- Tests ---------------------------------------------------------------

    @Test
    void retryReplaysTheExactOriginalResponse() throws Exception {
        UUID alice = user();
        UUID from = account(alice, "100.00"), to = account(user(), "0.00");

        var first = transfer(alice, "key-1", from, to, "25.00");
        var retry = transfer(alice, "key-1", from, to, "25.00");
        // "25" is the same request as "25.00".
        var retryOtherFormat = transfer(alice, "key-1", from, to, "25");

        assertEquals(201, first.getStatus());
        assertNull(first.getHeader(IdempotencyService.REPLAYED_HEADER));
        for (var replay : List.of(retry, retryOtherFormat)) {
            assertEquals(201, replay.getStatus());
            assertEquals("true", replay.getHeader(IdempotencyService.REPLAYED_HEADER));
            assertEquals(first.getContentAsString(), replay.getContentAsString(), "Replay must be byte-identical");
        }
        assertEquals(1, postingsFrom(from));
        assertMoney("75.00", balance(from));
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        UUID alice = user(), bob = user();
        UUID aliceAccount = account(alice, "100.00"), bobAccount = account(bob, "100.00");

        // The same key, used by two users for different requests, is two separate operations.
        assertEquals(201, withdraw(alice, "shared-key", aliceAccount, "10.00").getStatus());
        assertEquals(201, withdraw(bob, "shared-key", bobAccount, "30.00").getStatus());
        assertMoney("90.00", balance(aliceAccount));
        assertMoney("70.00", balance(bobAccount));
    }

    @Test
    void reusingAKeyForADifferentRequestIs422() throws Exception {
        UUID alice = user();
        UUID from = account(alice, "100.00"), to = account(user(), "0.00");
        assertEquals(201, transfer(alice, "key-2", from, to, "25.00").getStatus());

        assertEquals(422, transfer(alice, "key-2", from, to, "26.00").getStatus());
        assertEquals(422, withdraw(alice, "key-2", from, "25.00").getStatus());
        assertEquals(1, postingsFrom(from));
    }

    @Test
    void businessRejectionIsStoredAndReplayed() throws Exception {
        UUID alice = user();
        UUID account = account(alice, "10.00");

        var rejected = withdraw(alice, "key-3", account, "50.00");
        assertEquals(409, rejected.getStatus());
        assertEquals("application/problem+json", rejected.getContentType());

        // Even once funds are available, the same key returns the original outcome.
        assertEquals(201, deposit(alice, "key-3-deposit", account, "100.00").getStatus());
        var retry = withdraw(alice, "key-3", account, "50.00");
        assertEquals(409, retry.getStatus());
        assertEquals("true", retry.getHeader(IdempotencyService.REPLAYED_HEADER));
        assertEquals(rejected.getContentAsString(), retry.getContentAsString());

        // A new key is a new request.
        assertEquals(201, withdraw(alice, "key-3-again", account, "50.00").getStatus());
        assertMoney("60.00", balance(account));
    }

    @Test
    void forbiddenAndInvalidRequestsDoNotConsumeTheKey() throws Exception {
        UUID alice = user(), bob = user();
        UUID aliceAccount = account(alice, "100.00"), bobAccount = account(bob, "100.00");

        assertEquals(403, withdraw(bob, "key-4", aliceAccount, "10.00").getStatus());
        assertEquals(400, withdraw(bob, "key-4", bobAccount, "-1").getStatus());
        assertEquals(201, withdraw(bob, "key-4", bobAccount, "10.00").getStatus());
        assertMoney("100.00", balance(aliceAccount));
        assertMoney("90.00", balance(bobAccount));
    }

    @Test
    void keyMustBePresentAndConsistent() throws Exception {
        UUID alice = user();
        UUID account = account(alice, "100.00");

        assertEquals(400, withdraw(alice, null, account, "10.00").getStatus(), "No key at all");
        assertEquals(400, send(post("/api/v1/accounts/{id}/withdrawals", account), alice, "header-key",
                "{\"amount\":\"10.00\",\"requestKey\":\"body-key\"}").getStatus(), "Header and body disagree");
        assertEquals(400, withdraw(alice, "x".repeat(256), account, "10.00").getStatus(), "Key too long");
        // The legacy body field still works, and is the same key as the header.
        assertEquals(201, send(post("/api/v1/accounts/{id}/withdrawals", account), alice, null,
                "{\"amount\":\"10.00\",\"requestKey\":\"body-key\"}").getStatus());
        assertEquals("true", withdraw(alice, "body-key", account, "10.00").getHeader(IdempotencyService.REPLAYED_HEADER));
        assertMoney("90.00", balance(account));
    }

    @Test
    void expiredKeyCanBeReusedAndIsPruned() throws Exception {
        UUID alice = user();
        UUID account = account(alice, "100.00");
        assertEquals(201, withdraw(alice, "key-5", account, "10.00").getStatus());

        jdbc.update("UPDATE idempotency_keys SET created_at = created_at - INTERVAL '2 days', "
                + "expires_at = expires_at - INTERVAL '2 days' WHERE user_id = ? AND idempotency_key = 'key-5'", alice);
        // After expiry the key is free: a different request with it is new, not a 422.
        var reused = withdraw(alice, "key-5", account, "20.00");
        assertEquals(201, reused.getStatus());
        assertNull(reused.getHeader(IdempotencyService.REPLAYED_HEADER));
        assertMoney("70.00", balance(account));

        jdbc.update("UPDATE idempotency_keys SET created_at = created_at - INTERVAL '2 days', "
                + "expires_at = expires_at - INTERVAL '2 days' WHERE user_id = ?", alice);
        assertTrue(pruner.pruneExpired(Instant.now()) >= 1);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ?", Integer.class, alice));
    }

    @Test
    void crashAfterPostingButBeforeStoringTheResponseStillPostsOnce() throws Exception {
        UUID alice = user();
        UUID from = account(alice, "100.00"), to = account(user(), "0.00");
        var first = transfer(alice, "key-6", from, to, "40.00");
        assertEquals(201, first.getStatus());

        // Simulate the server stopping after the transfer committed but before the response was stored.
        jdbc.update("UPDATE idempotency_keys SET state = 'STARTED', response_status = NULL, response_body = NULL, "
                + "completed_at = NULL WHERE user_id = ? AND idempotency_key = 'key-6'", alice);

        var retry = transfer(alice, "key-6", from, to, "40.00");
        assertEquals(201, retry.getStatus());
        assertEquals(first.getContentAsString(), retry.getContentAsString(), "Same transaction returned");
        assertEquals(1, postingsFrom(from));
        assertMoney("60.00", balance(from));
    }

    @Test
    void simultaneousRetriesWithOneKeyPostOnceAndAgree() throws Exception {
        UUID alice = user();
        UUID from = account(alice, "1000.00"), to = account(user(), "0.00");
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++)
                futures.add(pool.submit(() -> { gate.await(); return transfer(alice, "key-7", from, to, "15.00"); }));
            gate.countDown();
            Set<String> bodies = new HashSet<>();
            for (var f : futures) {
                var response = f.get(1, TimeUnit.MINUTES);
                assertEquals(201, response.getStatus());
                bodies.add(response.getContentAsString());
            }
            assertEquals(1, bodies.size(), "Every caller must get the same response");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, postingsFrom(from));
        assertMoney("985.00", balance(from));
    }

    @Test
    void holdCreationIsIdempotentByHeader() throws Exception {
        UUID alice = user();
        UUID from = account(alice, "100.00"), to = account(user(), "0.00");
        String body = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"30.00\"}".formatted(from, to);
        var first = send(post("/api/v1/holds"), alice, "hold-key", body);
        var retry = send(post("/api/v1/holds"), alice, "hold-key", body);
        assertEquals(201, first.getStatus());
        assertEquals(first.getContentAsString(), retry.getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE source_account_id = ?", Integer.class, from));
    }
}
