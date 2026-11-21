package com.smartbank.transaction;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Linked transfers through the secured HTTP API, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class LinkedTransferApiTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReconciliation reconciliation;

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", id, id + "@t.local");
        return id;
    }

    private UUID account(UUID owner, String balance) {
        UUID id = LedgerTestData.openAccount(jdbc, "LINK-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
        if (owner != null) jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private static String leg(UUID from, UUID to, String amount) {
        return "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\"}".formatted(from, to, amount);
    }

    private MockHttpServletResponse linked(UUID user, String key, String... legs) throws Exception {
        return mvc.perform(post("/api/v1/transfers/linked").with(jwt().jwt(j -> j.subject(user.toString())))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transfers\":[" + String.join(",", legs) + "]}"))
                .andReturn().getResponse();
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private long legsTouching(UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions WHERE link_id IS NOT NULL "
                + "AND ? IN (source_account_id, destination_account_id)", Long.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void chargeCustomerPayMerchantAndTakeAFee() throws Exception {
        UUID customer = user();
        UUID wallet = account(customer, "100.00"), merchant = account(null, "0.00"), fees = account(null, "0.00");

        var response = linked(customer, "checkout-1", leg(wallet, merchant, "47.50"), leg(wallet, fees, "2.50"));

        assertEquals(201, response.getStatus());
        String body = response.getContentAsString();
        String linkId = JsonPath.read(body, "$.linkId");
        assertEquals(2, (int) JsonPath.read(body, "$.transfers.length()"));
        assertEquals(List.of(0, 1), JsonPath.read(body, "$.transfers[*].linkIndex"));
        assertEquals(List.of(linkId, linkId), JsonPath.read(body, "$.transfers[*].linkId"));
        assertMoney("50.00", balance(wallet));
        assertMoney("47.50", balance(merchant));
        assertMoney("2.50", balance(fees));
        // One transaction.posted event per leg, each carrying the link id.
        List<String> payloads = jdbc.queryForList("""
                SELECT o.payload FROM outbox_events o JOIN banking_transactions t ON t.id = o.aggregate_id
                WHERE t.link_id = ?::uuid ORDER BY t.link_index""", String.class, linkId);
        assertEquals(2, payloads.size());
        payloads.forEach(p -> assertEquals(linkId, JsonPath.read(p, "$.linkId")));
    }

    @Test
    void ifAnyLegFailsNothingPosts() throws Exception {
        UUID customer = user();
        UUID wallet = account(customer, "100.00"), savings = account(customer, "5.00"), merchant = account(null, "0.00");

        // The first leg alone would succeed; the second cannot, so neither posts.
        var response = linked(customer, "checkout-2", leg(wallet, merchant, "40.00"), leg(savings, merchant, "6.00"));

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("Insufficient funds in account " + savings));
        assertMoney("100.00", balance(wallet));
        assertMoney("5.00", balance(savings));
        assertEquals(0, legsTouching(merchant));
    }

    @Test
    void fundsAreCheckedOnTheNetChange() throws Exception {
        UUID owner = user();
        UUID a = account(owner, "100.00"), b = account(owner, "0.00"), c = account(null, "0.00");

        // b has nothing, but passes on what it receives in the same group.
        assertEquals(201, linked(owner, "net-1", leg(a, b, "60.00"), leg(b, c, "60.00")).getStatus());
        assertMoney("40.00", balance(a));
        assertMoney("0.00", balance(b));
        assertMoney("60.00", balance(c));
        // But not more than it receives.
        assertEquals(409, linked(owner, "net-2", leg(a, b, "10.00"), leg(b, c, "10.01")).getStatus());
        assertMoney("40.00", balance(a));
    }

    @Test
    void retriesAreIdempotentAndKeyReuseIsRejected() throws Exception {
        UUID owner = user();
        UUID a = account(owner, "100.00"), b = account(null, "0.00"), c = account(null, "0.00");
        var first = linked(owner, "group-key", leg(a, b, "10.00"), leg(a, c, "5.00"));
        var retry = linked(owner, "group-key", leg(a, b, "10.00"), leg(a, c, "5.00"));
        assertEquals(201, retry.getStatus());
        assertEquals(first.getContentAsString(), retry.getContentAsString());
        assertEquals("true", retry.getHeader("Idempotent-Replayed"));
        assertEquals(422, linked(owner, "group-key", leg(a, b, "10.00")).getStatus(), "Same key, different group");
        assertEquals(2, legsTouching(a));
        assertMoney("85.00", balance(a));
    }

    @Test
    void callerMustOwnEverySourceAccount() throws Exception {
        UUID owner = user(), other = user();
        UUID mine = account(owner, "100.00"), theirs = account(other, "100.00"), payee = account(null, "0.00");
        assertEquals(403, linked(owner, "steal", leg(mine, payee, "1.00"), leg(theirs, payee, "1.00")).getStatus());
        assertMoney("100.00", balance(theirs));
        assertEquals(0, legsTouching(payee));
    }

    @Test
    void invalidGroupsAreRejected() throws Exception {
        UUID owner = user();
        UUID a = account(owner, "100.00"), b = account(null, "0.00");
        assertEquals(400, linked(owner, "empty").getStatus(), "No legs");
        String[] tooMany = new String[21];
        java.util.Arrays.fill(tooMany, leg(a, b, "1.00"));
        assertEquals(400, linked(owner, "too-many", tooMany).getStatus(), "More than 20 legs");
        assertEquals(409, linked(owner, "same", leg(a, b, "1.00"), leg(a, a, "1.00")).getStatus(), "A leg to itself");
        assertEquals(0, legsTouching(b));
    }
}
