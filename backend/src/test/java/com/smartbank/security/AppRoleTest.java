package com.smartbank.security;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.events.WebhookDispatcher;
import com.smartbank.fx.FxService;
import com.smartbank.idempotency.IdempotencyKeyPruner;
import com.smartbank.ledger.BalanceCheckpoints;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.ledger.LedgerSealer;
import com.smartbank.ratelimit.RateLimitPruner;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.HoldExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The application running as the restricted smartbank_app role (V16), as it does
 * outside tests. Every other test connects as the database owner so it can set up
 * data directly; this one proves the role has every privilege the app needs, and
 * none that would let it rewrite the books.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {
        "smartbank.ledger.seal-interval=PT1H",
        "smartbank.webhooks.allow-http=true",
        "smartbank.webhooks.allow-private-targets=true"})
@AutoConfigureMockMvc
class AppRoleTest {
    static final String APP_PASSWORD = "app-role-test-password";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "smartbank_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("smartbank.db.app-password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate app;
    @Autowired FxService fx;
    @Autowired LedgerSealer sealer;
    @Autowired BalanceCheckpoints checkpoints;
    @Autowired IdempotencyKeyPruner idempotencyPruner;
    @Autowired RateLimitPruner rateLimitPruner;
    @Autowired HoldExpiryJob holdExpiry;
    @Autowired WebhookDispatcher webhooks;
    @Autowired LedgerReconciliation reconciliation;

    /** The schema owner, for setting up data the API cannot create (users with accounts, administrators). */
    private JdbcTemplate owner;
    private UUID customer, payee, admin, wallet, shop, dollars;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        customer = user("CUSTOMER");
        payee = user("CUSTOMER");
        admin = user("ADMIN");
        wallet = account(customer, "1000.00", "CAD");
        shop = account(payee, "0.00", "CAD");
        dollars = account(customer, "0.00", "USD");
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', ?)", id, id + "@t.local", role);
        return id;
    }

    private UUID account(UUID ownerId, String balance, String currency) {
        UUID id = LedgerTestData.openAccount(owner, "ROLE-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), currency);
        owner.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", ownerId, id);
        return id;
    }

    private String call(MockHttpServletRequestBuilder request, UUID as, String body, int expected) throws Exception {
        request = request.with(jwt().jwt(j -> j.subject(as.toString()))).header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        MockHttpServletResponse r = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, r.getStatus(), r.getContentAsString());
        return r.getContentAsString();
    }

    @Test
    void theAppConnectsAsTheRestrictedRole() {
        assertEquals("smartbank_app", app.queryForObject("SELECT current_user", String.class));
        assertEquals(0, app.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user", Integer.class));
    }

    @Test
    void theRoleCannotRewriteTheBooks() {
        // A posting to tamper with.
        owner.update("UPDATE accounts SET balance = balance WHERE id = ?", wallet);
        List<String> attempts = List.of(
                "UPDATE banking_transactions SET amount = amount + 1",
                "DELETE FROM banking_transactions",
                "DELETE FROM ledger_entries",
                "UPDATE ledger_entries SET amount = 0",
                "TRUNCATE ledger_entries",
                "TRUNCATE banking_transactions CASCADE",
                "ALTER TABLE ledger_entries DISABLE TRIGGER ALL",
                "ALTER TABLE banking_transactions DISABLE TRIGGER USER",
                "DROP TRIGGER ledger_entries_append_only ON ledger_entries",
                "DELETE FROM ledger_seals",
                "UPDATE ledger_seals SET hash = hash",
                "DELETE FROM outbox_events",
                "DELETE FROM accounts",
                "DELETE FROM holds",
                "CREATE TABLE sneaky (id INT)",
                "ALTER ROLE smartbank_app SUPERUSER",
                "SET session_replication_role = replica");
        for (String sql : attempts)
            assertThrows(DataAccessException.class, () -> app.execute(sql), () -> "The app role was allowed: " + sql);
        assertTrue(reconciliation.run().isClean());
    }

    @Test
    void everyTableHasADecidedSetOfPrivileges() {
        // A new table without grants would break the app; one with too many would weaken this.
        List<String> ungranted = owner.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v') AND c.relname <> 'flyway_schema_history'
                  AND NOT has_table_privilege('smartbank_app', c.oid, 'SELECT')""", String.class);
        assertEquals(List.of(), ungranted, "Tables the app role cannot read; grant them in a migration");
        List<String> deletable = owner.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND (has_table_privilege('smartbank_app', c.oid, 'DELETE')
                       OR has_table_privilege('smartbank_app', c.oid, 'TRUNCATE'))
                ORDER BY 1""", String.class);
        assertEquals(List.of("idempotency_keys", "rate_limit_buckets"), deletable);
        Set<String> history = Set.of("banking_transactions", "ledger_entries", "outbox_events", "ledger_seals",
                "ledger_sealed_transactions", "ledger_checkpoints", "ledger_checkpoint_runs", "fx_rates");
        // (banking_transactions grants UPDATE on one column only, for row locks; its trigger refuses updates.)
        for (String table : history)
            assertFalse(owner.queryForObject("SELECT has_table_privilege('smartbank_app', ?, 'UPDATE')", Boolean.class, table),
                    table + " must not be updatable");
    }

    @Test
    void everyOperationWorksAsTheRestrictedRole() throws Exception {
        // Sign-up and login.
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new.customer@t.local\",\"password\":\"a-long-enough-password\"}")).andReturn();
        assertEquals(1, owner.queryForObject("SELECT COUNT(*) FROM app_users WHERE email = 'new.customer@t.local'", Integer.class));

        // Money movement, through the batcher and directly.
        call(post("/api/v1/accounts/" + wallet + "/deposits"), customer, "{\"amount\":\"100.00\"}", 201);
        call(post("/api/v1/accounts/" + wallet + "/withdrawals"), customer, "{\"amount\":\"50.00\"}", 201);
        String transfer = call(post("/api/v1/transfers"), customer,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"100.00\"}".formatted(wallet, shop), 201);
        call(post("/api/v1/transfers/linked"), customer, """
                {"transfers":[{"fromAccountId":"%s","toAccountId":"%s","amount":"10.00"},
                              {"fromAccountId":"%s","toAccountId":"%s","amount":"5.00"}]}""".formatted(wallet, shop, wallet, shop), 201);
        call(post("/api/v1/transactions/" + JsonPath.read(transfer, "$.id") + "/reversals"), payee, "{\"amount\":\"25.00\"}", 201);
        call(post("/api/v1/accounts/" + wallet + "/fees"), admin, "{\"amount\":\"2.50\"}", 201);

        // Holds: posted, voided, and one left to expire.
        String held = call(post("/api/v1/holds"), customer,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"20.00\"}".formatted(wallet, shop), 201);
        call(post("/api/v1/holds/" + JsonPath.read(held, "$.id") + "/post"), payee, null, 200);
        String voided = call(post("/api/v1/holds"), customer,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"30.00\"}".formatted(wallet, shop), 201);
        call(post("/api/v1/holds/" + JsonPath.read(voided, "$.id") + "/void"), payee, null, 200);
        call(post("/api/v1/holds"), customer,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"5.00\",\"expiresInSeconds\":60}".formatted(wallet, shop), 201);

        // FX: an administrator's rate, a quote, its execution.
        fx.setRate("CAD", "USD", new BigDecimal("0.7300"), admin);
        String quote = call(post("/api/v1/fx/quotes"), customer,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"100.00\"}".formatted(wallet, dollars), 201);
        call(post("/api/v1/fx/transfers"), customer, "{\"quoteId\":\"" + JsonPath.read(quote, "$.id") + "\"}", 201);

        // Webhooks: an endpoint, then a delivery attempt (to a closed port, so it is retried later).
        call(post("/api/v1/webhook-endpoints"), customer, "{\"url\":\"http://127.0.0.1:9/hook\"}", 201);
        call(post("/api/v1/accounts/" + wallet + "/deposits"), customer, "{\"amount\":\"1.00\"}", 201);
        webhooks.deliverDue(Instant.now());

        // Reads.
        call(get("/api/v1/accounts/" + wallet + "/statement"), customer, null, 200);
        call(get("/api/v1/ledger/trial-balance?currency=CAD"), admin, null, 200);

        // Background jobs.
        assertFalse(sealer.sealPending().isEmpty());
        assertTrue(sealer.verify().intact());
        checkpoints.checkpointDue(Instant.now());
        assertEquals(1, holdExpiry.expireDue(Instant.now().plus(Duration.ofMinutes(5))));
        idempotencyPruner.pruneExpired(Instant.now().plus(Duration.ofDays(2)));
        rateLimitPruner.run();

        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }
}
