package com.smartbank.agent;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/** AI agents paying under spending mandates, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = "smartbank.ledger.seal-interval=PT1H")
@AutoConfigureMockMvc
class AgentMandateTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentPaymentService payments;
    @Autowired LedgerReconciliation reconciliation;

    private UUID owner, stranger, wallet, shopOwner, shop, otherShop;
    private String shopNumber, otherShopNumber;

    @BeforeEach
    void setUp() {
        owner = user();
        stranger = user();
        shopOwner = user();
        wallet = account(owner, "1000.00", "CAD");
        shop = account(shopOwner, "0.00", "CAD");
        otherShop = account(null, "0.00", "CAD");
        shopNumber = number(shop);
        otherShopNumber = number(otherShop);
    }

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    // ---- Fixtures --------------------------------------------------------------------

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", id, id + "@t.local");
        return id;
    }

    private UUID account(UUID ownerId, String balance, String currency) {
        UUID id = LedgerTestData.openAccount(jdbc, "AG-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(balance), currency);
        if (ownerId != null) jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", ownerId, id);
        return id;
    }

    private String number(UUID account) {
        return jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class, account);
    }

    private BigDecimal column(String column, UUID account) {
        return jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private static void assertMoney(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                () -> "expected " + expected + " but was " + actual);
    }

    private MockHttpServletResponse asUser(MockHttpServletRequestBuilder request, UUID user, String body) throws Exception {
        request = request.with(jwt().jwt(j -> j.subject(user.toString())));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse asAgent(MockHttpServletRequestBuilder request, String key, String body) throws Exception {
        request = request.header("Authorization", "Agent " + key);
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse();
    }

    /** Creates a mandate on the owner's wallet; returns {mandate id, API key}. */
    private String[] mandate(String perPayment, String daily, boolean ask, String... payees) throws Exception {
        String allowed = payees.length == 0 ? "" : ",\"allowedPayeeAccountNumbers\":[\"" + String.join("\",\"", payees) + "\"]";
        var r = asUser(post("/api/v1/mandates"), owner, """
                {"accountId":"%s","name":"Shopping agent","perPaymentLimit":"%s","dailyLimit":"%s","askAboveLimit":%s%s}"""
                .formatted(wallet, perPayment, daily, ask, allowed));
        assertEquals(201, r.getStatus(), r.getContentAsString());
        String body = r.getContentAsString();
        return new String[] { JsonPath.read(body, "$.mandate.id"), JsonPath.read(body, "$.apiKey") };
    }

    private MockHttpServletResponse pay(String key, String to, String amount, String idempotencyKey) throws Exception {
        return asAgent(post("/api/v1/agent/payments").header("Idempotency-Key", idempotencyKey), key,
                "{\"toAccountNumber\":\"%s\",\"amount\":\"%s\",\"intent\":\"Buy headphones\"}".formatted(to, amount));
    }

    private String paid(String key, String to, String amount, String expectedStatus) throws Exception {
        var r = pay(key, to, amount, UUID.randomUUID().toString());
        assertEquals(201, r.getStatus(), r.getContentAsString());
        assertEquals(expectedStatus, JsonPath.read(r.getContentAsString(), "$.status"), r.getContentAsString());
        return r.getContentAsString();
    }

    // ---- Paying within the limits ---------------------------------------------------------

    @Test
    void paymentWithinTheLimitsPostsAtOnceAndTheLedgerNamesTheMandate() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        assertTrue(m[1].startsWith("sbk_agent_"));
        String body = paid(m[1], shopNumber, "30.00", "POSTED");

        assertMoney("970.00", column("balance", wallet));
        assertMoney("30.00", column("balance", shop));
        UUID tx = UUID.fromString(JsonPath.read(body, "$.transactionId"));
        assertEquals(UUID.fromString(m[0]),
                jdbc.queryForObject("SELECT agent_mandate_id FROM banking_transactions WHERE id = ?", UUID.class, tx));
        // The transfer is initiated by the customer the agent acts for.
        assertEquals(owner, jdbc.queryForObject("SELECT initiated_by FROM banking_transactions WHERE id = ?", UUID.class, tx));

        String mine = asAgent(get("/api/v1/agent/mandate"), m[1], null).getContentAsString();
        assertMoney("30.00", JsonPath.read(mine, "$.spentToday"));
        assertMoney("70.00", JsonPath.read(mine, "$.remainingToday"));
        // The key is never shown again, and never stored.
        assertFalse(mine.contains(m[1]));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_mandates WHERE key_hash = ? OR key_prefix = ?",
                Integer.class, m[1], m[1]));
    }

    @Test
    void retryWithTheSameIdempotencyKeyReturnsTheSamePaymentAndPaysOnce() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        var first = pay(m[1], shopNumber, "20.00", "order-1");
        var retry = pay(m[1], shopNumber, "20.00", "order-1");
        assertEquals(201, retry.getStatus());
        assertEquals("true", retry.getHeader("Idempotent-Replayed"));
        assertEquals((String) JsonPath.read(first.getContentAsString(), "$.id"), JsonPath.read(retry.getContentAsString(), "$.id"));
        assertEquals(422, pay(m[1], shopNumber, "25.00", "order-1").getStatus());
        assertMoney("980.00", column("balance", wallet));
    }

    // ---- Over the limits ------------------------------------------------------------------

    @Test
    void paymentOverTheLimitIsHeldUntilTheOwnerApprovesIt() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        String body = paid(m[1], shopNumber, "80.00", "PENDING_APPROVAL");
        UUID payment = UUID.fromString(JsonPath.read(body, "$.id"));
        UUID hold = UUID.fromString(JsonPath.read(body, "$.holdId"));
        assertTrue(((String) JsonPath.read(body, "$.reason")).contains("per-payment limit"));
        assertNotNull(JsonPath.read(body, "$.approvalExpiresAt"));
        // The money is reserved, not yet paid.
        assertMoney("1000.00", column("balance", wallet));
        assertMoney("80.00", column("pending_debits", wallet));

        // The payee cannot capture it as if it were an ordinary hold.
        assertEquals(409, asUser(post("/api/v1/holds/" + hold + "/post"), shopOwner, null).getStatus());
        // Only the owner can decide.
        assertEquals(404, asUser(post("/api/v1/agent-payments/" + payment + "/approve"), stranger, null).getStatus());

        var waiting = asUser(get("/api/v1/agent-payments/awaiting-approval"), owner, null).getContentAsString();
        assertEquals(List.of(payment.toString()), JsonPath.read(waiting, "$[*].id"));

        var approved = asUser(post("/api/v1/agent-payments/" + payment + "/approve"), owner, null);
        assertEquals(200, approved.getStatus(), approved.getContentAsString());
        assertEquals("APPROVED", JsonPath.read(approved.getContentAsString(), "$.status"));
        assertMoney("920.00", column("balance", wallet));
        assertMoney("0.00", column("pending_debits", wallet));
        assertMoney("80.00", column("balance", shop));
        UUID tx = UUID.fromString(JsonPath.read(approved.getContentAsString(), "$.transactionId"));
        assertEquals(UUID.fromString(m[0]),
                jdbc.queryForObject("SELECT agent_mandate_id FROM banking_transactions WHERE id = ?", UUID.class, tx));
        // Approving again is a no-op; the agent sees the decision.
        assertEquals(200, asUser(post("/api/v1/agent-payments/" + payment + "/approve"), owner, null).getStatus());
        assertEquals("APPROVED", JsonPath.read(asAgent(get("/api/v1/agent/payments/" + payment), m[1], null)
                .getContentAsString(), "$.status"));
        // Approved spending counts against the day.
        assertMoney("80.00", JsonPath.read(asAgent(get("/api/v1/agent/mandate"), m[1], null).getContentAsString(), "$.spentToday"));
        assertMoney("920.00", column("balance", wallet));
    }

    @Test
    void decliningReleasesTheHeldMoney() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        UUID payment = UUID.fromString(JsonPath.read(paid(m[1], shopNumber, "60.00", "PENDING_APPROVAL"), "$.id"));
        var declined = asUser(post("/api/v1/agent-payments/" + payment + "/decline"), owner, null);
        assertEquals("DECLINED", JsonPath.read(declined.getContentAsString(), "$.status"));
        assertMoney("1000.00", column("balance", wallet));
        assertMoney("0.00", column("pending_debits", wallet));
        assertEquals(409, asUser(post("/api/v1/agent-payments/" + payment + "/approve"), owner, null).getStatus());
    }

    @Test
    void overTheDailyLimitIsRejectedWhenTheMandateDoesNotAsk() throws Exception {
        String[] m = mandate("50.00", "60.00", false);
        paid(m[1], shopNumber, "50.00", "POSTED");
        String body = paid(m[1], shopNumber, "20.00", "REJECTED");
        assertTrue(((String) JsonPath.read(body, "$.reason")).contains("daily limit"));
        paid(m[1], shopNumber, "10.00", "POSTED");  // exactly at the limit
        assertMoney("940.00", column("balance", wallet));
    }

    @Test
    void payeesOutsideTheAllowedListAreRejectedAndEveryAttemptIsRecorded() throws Exception {
        String[] m = mandate("50.00", "100.00", true, shopNumber);
        paid(m[1], shopNumber, "10.00", "POSTED");
        paid(m[1], otherShopNumber, "10.00", "REJECTED");
        assertMoney("0.00", column("balance", otherShop));

        String trail = asUser(get("/api/v1/mandates/" + m[0] + "/payments"), owner, null).getContentAsString();
        assertEquals(List.of("REJECTED", "POSTED"), JsonPath.read(trail, "$[*].status"));
        assertEquals(404, asUser(get("/api/v1/mandates/" + m[0] + "/payments"), stranger, null).getStatus());
    }

    @Test
    void tooManyPaymentsAwaitingApprovalAreRejected() throws Exception {
        String[] m = mandate("10.00", "100.00", true);
        for (int i = 0; i < 5; i++) paid(m[1], shopNumber, "20.00", "PENDING_APPROVAL");
        paid(m[1], shopNumber, "20.00", "REJECTED");
        assertMoney("100.00", column("pending_debits", wallet));
    }

    // ---- The limit under concurrency ------------------------------------------------------------

    @Test
    void simultaneousPaymentsNeverTogetherExceedTheDailyLimit() throws Exception {
        String[] m = mandate("3.00", "100.00", false);
        AgentPrincipal agent = new AgentPrincipal(UUID.fromString(m[0]), owner, wallet);
        int agents = 50;
        ExecutorService pool = Executors.newFixedThreadPool(agents);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < agents; i++)
                results.add(pool.submit(() -> {
                    start.await();
                    return payments.pay(agent, new AgentPaymentService.PaymentRequest(shopNumber, new BigDecimal("3.00"),
                            "Buy a sticker"), UUID.randomUUID().toString()).status();
                }));
            start.countDown();
            Map<String, Integer> counts = new TreeMap<>();
            for (var f : results) counts.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            // 33 x 3.00 = 99.00 fits in 100.00; a 34th would not.
            assertEquals(Map.of("POSTED", 33, "REJECTED", 17), counts);
        } finally {
            pool.shutdownNow();
        }
        assertMoney("901.00", column("balance", wallet));
        assertMoney("99.00", jdbc.queryForObject(
                "SELECT auto_spent FROM agent_mandate_usage WHERE mandate_id = ?", BigDecimal.class, UUID.fromString(m[0])));
    }

    // ---- Revocation and access --------------------------------------------------------------

    @Test
    void revokingStopsTheKeyAtOnceAndDeclinesWhatAwaitsApproval() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        UUID payment = UUID.fromString(JsonPath.read(paid(m[1], shopNumber, "70.00", "PENDING_APPROVAL"), "$.id"));
        var revoked = asUser(post("/api/v1/mandates/" + m[0] + "/revoke"), owner, null);
        assertEquals("REVOKED", JsonPath.read(revoked.getContentAsString(), "$.status"));

        assertEquals(401, pay(m[1], shopNumber, "10.00", "after-revoke").getStatus());
        assertEquals(401, asAgent(get("/api/v1/agent/mandate"), m[1], null).getStatus());
        var p = asUser(get("/api/v1/agent-payments/" + payment), owner, null).getContentAsString();
        assertEquals("DECLINED", JsonPath.read(p, "$.status"));
        assertEquals("Mandate revoked", JsonPath.read(p, "$.reason"));
        assertMoney("0.00", column("pending_debits", wallet));
        // Even a payment already past the key check cannot commit after revocation.
        AgentPrincipal agent = new AgentPrincipal(UUID.fromString(m[0]), owner, wallet);
        var e = assertThrows(com.smartbank.transaction.BankingException.class, () -> payments.pay(agent,
                new AgentPaymentService.PaymentRequest(shopNumber, new BigDecimal("1.00"), "late"), UUID.randomUUID().toString()));
        assertTrue(e.getMessage().contains("revoked"));
        assertMoney("1000.00", column("balance", wallet));
    }

    @Test
    void agentKeysOnlyWorkOnTheAgentApiAndOnlyOwnersCreateMandates() throws Exception {
        String[] m = mandate("50.00", "100.00", true);
        assertEquals(401, asAgent(get("/api/v1/accounts"), m[1], null).getStatus());
        assertEquals(401, asAgent(get("/api/v1/agent/mandate"), "sbk_agent_not-a-real-key", null).getStatus());
        assertEquals(403, asUser(get("/api/v1/agent/mandate"), owner, null).getStatus());
        assertEquals(403, asUser(post("/api/v1/mandates"), stranger, """
                {"accountId":"%s","name":"x","perPaymentLimit":"1.00","dailyLimit":"1.00"}""".formatted(wallet)).getStatus());
        assertEquals(400, asUser(post("/api/v1/mandates"), owner, """
                {"accountId":"%s","name":"x","perPaymentLimit":"10.00","dailyLimit":"5.00"}""".formatted(wallet)).getStatus());
        // An agent cannot see another mandate's payments.
        String[] other = mandate("50.00", "100.00", true);
        UUID payment = UUID.fromString(JsonPath.read(paid(other[1], shopNumber, "5.00", "POSTED"), "$.id"));
        assertEquals(404, asAgent(get("/api/v1/agent/payments/" + payment), m[1], null).getStatus());
    }

    // ---- The database's own guarantees ----------------------------------------------------------

    @Test
    void theDatabaseRefusesUsageOverTheLimitAndChangesToAMandate() throws Exception {
        UUID mandate = UUID.fromString(mandate("50.00", "100.00", true)[0]);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO agent_mandate_usage (mandate_id, day, auto_spent) VALUES (?, current_date, 100.01)", mandate));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE agent_mandates SET daily_limit = 1000000 WHERE id = ?", mandate));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM agent_mandates WHERE id = ?", mandate));
    }
}
