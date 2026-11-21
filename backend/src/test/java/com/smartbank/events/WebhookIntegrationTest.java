package com.smartbank.events;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transactional outbox and webhook delivery, end to end: events written with the
 * change they describe, routed to subscribed endpoints, delivered over real HTTP
 * with HMAC signatures, retried with backoff, and dead-lettered.
 *
 * The scheduled worker is slowed to once an hour here; tests drive it directly
 * with explicit times so retry timing is deterministic.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {
        "smartbank.webhooks.poll-interval=PT1H",
        "smartbank.webhooks.base-backoff=PT10S",
        "smartbank.webhooks.max-attempts=4",
        "smartbank.webhooks.request-timeout=PT2S"})
@AutoConfigureMockMvc
class WebhookIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingService banking;
    @Autowired HoldService holds;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired LedgerReconciliation reconciliation;

    /** A real HTTP receiver: records requests, verifies signatures, can be told to fail. */
    static final class Receiver {
        record Received(String body, String signature, String eventId, String deliveryId, int attempt) { }

        final HttpServer server;
        final List<Received> received = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile int failureStatus = 500;
        volatile long delayMs = 0;

        Receiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.createContext("/hook", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var h = exchange.getRequestHeaders();
                received.add(new Received(body, h.getFirst(WebhookSigner.SIGNATURE_HEADER),
                        h.getFirst(WebhookSigner.EVENT_ID_HEADER), h.getFirst(WebhookSigner.DELIVERY_ID_HEADER),
                        Integer.parseInt(h.getFirst(WebhookSigner.ATTEMPT_HEADER))));
                try { if (delayMs > 0) Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                int status = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? failureStatus : 204;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook"; }

        List<Received> forEvent(String type) {
            return received.stream().filter(r -> type.equals(JsonPath.read(r.body(), "$.type"))).toList();
        }
    }

    Receiver receiver;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = new Receiver();
    }

    @AfterEach
    void stopReceiverAndReconcile() {
        receiver.server.stop(0);
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books or outbox out of balance: " + report);
    }

    // ---- Helpers -------------------------------------------------------------

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')",
                id, id + "@test.local");
        return id;
    }

    private UUID account(UUID owner, String balance) {
        UUID id = LedgerTestData.openAccount(jdbc, "HOOK-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private static RequestPostProcessor as(UUID user) {
        return jwt().jwt(j -> j.subject(user.toString()));
    }

    /** Registers an endpoint through the API; returns its id and secret. */
    private String[] register(UUID owner, String eventTypesJson) throws Exception {
        String body = eventTypesJson == null ? "{\"url\":\"%s\"}".formatted(receiver.url())
                : "{\"url\":\"%s\",\"eventTypes\":%s}".formatted(receiver.url(), eventTypesJson);
        String json = mvc.perform(post("/api/v1/webhook-endpoints").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[] { JsonPath.read(json, "$.id"), JsonPath.read(json, "$.secret") };
    }

    private TransactionResponse transfer(UUID from, UUID to, String amount) {
        return banking.transfer(new TransferRequest(from, to, new BigDecimal(amount), "hook-" + UUID.randomUUID()));
    }

    private Map<String, Object> delivery(String endpointId) {
        return jdbc.queryForMap("SELECT * FROM webhook_deliveries WHERE endpoint_id = ?::uuid", endpointId);
    }

    private static boolean signatureValid(String secret, String header, String body) throws Exception {
        String t = header.split(",")[0].substring(2);
        String v1 = header.split(",")[1].substring(3);
        String expected = WebhookSigner.hmac(secret, t + "." + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Outbox: written with the change ------------------------------------

    @Test
    void eventsExistOnlyForChangesThatCommitted() {
        UUID a = account(user(), "100.00"), b = account(user(), "0.00");
        String key = "outbox-" + UUID.randomUUID();
        TransactionResponse tx = banking.transfer(new TransferRequest(a, b, new BigDecimal("30.00"), key));
        banking.transfer(new TransferRequest(a, b, new BigDecimal("30.00"), key));  // replay: no new event
        assertThrows(BankingException.class, () -> transfer(a, b, "500.00"));     // rolled back: no event

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE ? = ANY (account_ids)", Integer.class, a));
        assertEquals(tx.id(), jdbc.queryForObject(
                "SELECT aggregate_id FROM outbox_events WHERE ? = ANY (account_ids)", UUID.class, a));
    }

    @Test
    void outboxIsAppendOnly() {
        UUID a = account(user(), "100.00"), b = account(user(), "0.00");
        TransactionResponse tx = transfer(a, b, "1.00");
        assertThrows(Exception.class, () -> jdbc.update("UPDATE outbox_events SET payload = '{}' WHERE aggregate_id = ?", tx.id()));
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", tx.id()));
    }

    // ---- Delivery ----------------------------------------------------------

    @Test
    void deliversSignedEventsToBothPartiesOnly() throws Exception {
        UUID payer = user(), payee = user(), stranger = user();
        UUID from = account(payer, "100.00"), to = account(payee, "0.00");
        account(stranger, "100.00");
        String payerSecret = register(payer, null)[1];
        register(payee, null);
        register(stranger, null);

        TransactionResponse tx = transfer(from, to, "12.34");
        assertEquals(2, dispatcher.deliverDue(Instant.now()), "Payer and payee endpoints, not the stranger's");

        List<Receiver.Received> posted = receiver.forEvent("transaction.posted");
        assertEquals(2, posted.size());
        Receiver.Received first = posted.stream().filter(r -> {
            try { return signatureValid(payerSecret, r.signature(), r.body()); } catch (Exception e) { return false; }
        }).findFirst().orElseThrow(() -> new AssertionError("No delivery verified with the payer's secret"));
        assertEquals(tx.id().toString(), JsonPath.read(first.body(), "$.data.id"));
        assertEquals(12.34, ((Number) JsonPath.read(first.body(), "$.data.amount")).doubleValue());
        assertEquals(first.eventId(), JsonPath.read(first.body(), "$.id"));
        assertFalse(signatureValid("whsec_wrong", first.signature(), first.body()));
        assertFalse(signatureValid(payerSecret, first.signature(), first.body().replace("12.34", "99.99")),
                "Tampered body must fail verification");
    }

    @Test
    void endpointsReceiveOnlySubscribedEventTypes() throws Exception {
        UUID payer = user(), payee = user();
        UUID from = account(payer, "100.00"), to = account(payee, "0.00");
        register(payee, "[\"hold.posted\"]");

        HoldResponse hold = holds.create(new CreateHoldRequest(from, to, new BigDecimal("40.00"), "hook-h-" + from, null));
        holds.post(hold.id(), new BigDecimal("25.00"));
        dispatcher.deliverDue(Instant.now());

        assertEquals(1, receiver.received.size(), "Only hold.posted, not hold.created or transaction.posted");
        String body = receiver.received.get(0).body();
        assertEquals("hold.posted", JsonPath.read(body, "$.type"));
        assertEquals("POSTED", JsonPath.read(body, "$.data.status"));
    }

    @Test
    void failedDeliveriesAreRetriedWithBackoffThenSucceed() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "100.00"), to = account(user(), "0.00");
        String endpointId = register(owner, null)[0];
        transfer(from, to, "5.00");
        receiver.failuresLeft.set(2);

        Instant t0 = Instant.now();
        assertEquals(1, dispatcher.deliverDue(t0));                        // attempt 1: 500
        assertEquals(0, dispatcher.deliverDue(t0.plusSeconds(5)), "Not due before the 10 s backoff");
        assertEquals(1, dispatcher.deliverDue(t0.plusSeconds(13)));        // attempt 2: 500, backoff now 20 s
        assertEquals(0, dispatcher.deliverDue(t0.plusSeconds(25)));
        assertEquals(1, dispatcher.deliverDue(t0.plusSeconds(13 + 25)));   // attempt 3: success

        var d = delivery(endpointId);
        assertEquals("DELIVERED", d.get("status"));
        assertEquals(3, d.get("attempts"));
        assertEquals(204, d.get("last_status_code"));
        // Every attempt carries the same event id and identical body, so the receiver can de-duplicate.
        assertEquals(1, receiver.received.stream().map(Receiver.Received::eventId).distinct().count());
        assertEquals(1, receiver.received.stream().map(Receiver.Received::body).distinct().count());
        assertEquals(List.of(1, 2, 3), receiver.received.stream().map(Receiver.Received::attempt).toList());
    }

    @Test
    void deliveryIsDeadLetteredAfterMaxAttempts() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "100.00"), to = account(user(), "0.00");
        String endpointId = register(owner, null)[0];
        transfer(from, to, "5.00");
        receiver.failuresLeft.set(Integer.MAX_VALUE);
        receiver.failureStatus = 503;

        Instant t = Instant.now();
        for (int i = 0; i < 10; i++) {
            dispatcher.deliverDue(t);
            t = t.plus(Duration.ofHours(1));
        }
        var d = delivery(endpointId);
        assertEquals("FAILED", d.get("status"));
        assertEquals(4, d.get("attempts"), "max-attempts is 4 in this test");
        assertEquals(503, d.get("last_status_code"));
        assertEquals(4, receiver.received.size());

        // The owner can see it through the API.
        mvc.perform(get("/api/v1/webhook-endpoints/{id}/deliveries", endpointId).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].status").value("FAILED"));
    }

    @Test
    void unreachableAndSlowEndpointsAreRetriedNotLost() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "100.00"), to = account(user(), "0.00");
        String endpointId = register(owner, null)[0];
        transfer(from, to, "5.00");

        receiver.delayMs = 3_000;  // longer than the 2 s request timeout
        Instant t0 = Instant.now();
        dispatcher.deliverDue(t0);
        assertEquals("PENDING", delivery(endpointId).get("status"));
        assertTrue(String.valueOf(delivery(endpointId).get("last_error")).contains("Timeout"));

        receiver.delayMs = 0;
        dispatcher.deliverDue(t0.plusSeconds(15));
        assertEquals("DELIVERED", delivery(endpointId).get("status"));
    }

    @Test
    void leasedDeliveryIsRetriedAfterTheLeaseExpires() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "100.00"), to = account(user(), "0.00");
        String endpointId = register(owner, null)[0];
        transfer(from, to, "5.00");

        // Simulate a worker that claimed the delivery and then died.
        Instant t0 = Instant.now();
        jdbc.update("UPDATE webhook_deliveries SET locked_until = ?, attempts = 1 WHERE endpoint_id = ?::uuid",
                java.sql.Timestamp.from(t0.plusSeconds(60)), endpointId);
        assertEquals(0, dispatcher.deliverDue(t0), "Still leased by the dead worker");
        assertEquals(1, dispatcher.deliverDue(t0.plusSeconds(61)));
        assertEquals("DELIVERED", delivery(endpointId).get("status"));
    }

    @Test
    void concurrentWorkersDeliverEachEventOnce() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "10000.00"), to = account(user(), "0.00");
        register(owner, null);
        int events = 120;
        for (int i = 0; i < events; i++) transfer(from, to, "1.00");

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Instant now = Instant.now();
            List<Future<Integer>> workers = new ArrayList<>();
            for (int w = 0; w < 6; w++)
                workers.add(pool.submit(() -> { gate.await(); return dispatcher.deliverDue(now); }));
            gate.countDown();
            int attempts = 0;
            for (var f : workers) attempts += f.get(2, TimeUnit.MINUTES);
            assertEquals(events, attempts, "Each delivery claimed by exactly one worker");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(events, receiver.received.size());
        assertEquals(events, receiver.received.stream().map(Receiver.Received::deliveryId).distinct().count());
    }

    @Test
    void deactivatedEndpointStopsReceiving() throws Exception {
        UUID owner = user();
        UUID from = account(owner, "100.00"), to = account(user(), "0.00");
        String endpointId = register(owner, null)[0];
        transfer(from, to, "1.00");  // queued before deactivation
        mvc.perform(delete("/api/v1/webhook-endpoints/{id}", endpointId).with(as(owner))).andExpect(status().isNoContent());
        transfer(from, to, "1.00");  // after: not queued

        assertEquals(0, dispatcher.deliverDue(Instant.now()));
        assertEquals(0, receiver.received.size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_deliveries WHERE endpoint_id = ?::uuid", Integer.class, endpointId));
    }

    @Test
    void endpointApiValidatesAndIsPrivate() throws Exception {
        UUID owner = user(), other = user();
        mvc.perform(post("/api/v1/webhook-endpoints").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://example.com/x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/webhook-endpoints").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/x\",\"eventTypes\":[\"money.vanished\"]}"))
                .andExpect(status().isBadRequest());
        String endpointId = register(owner, null)[0];

        String list = mvc.perform(get("/api/v1/webhook-endpoints").with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNull(JsonPath.read(list, "$[0].secret"), "The secret is shown only at creation");
        mvc.perform(get("/api/v1/webhook-endpoints/{id}/deliveries", endpointId).with(as(other)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/webhook-endpoints/{id}", endpointId).with(as(other)))
                .andExpect(status().isNotFound());
    }
}
