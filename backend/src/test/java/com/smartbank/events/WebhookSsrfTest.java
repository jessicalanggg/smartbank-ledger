package com.smartbank.events;

import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.TransferRequest;
import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SSRF protection with the production defaults: https only, no internal targets. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {"smartbank.webhooks.poll-interval=PT1H", "smartbank.webhooks.max-attempts=2"})
@AutoConfigureMockMvc
class WebhookSsrfTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired BankingService banking;
    @Autowired SecretCipher cipher;

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", id, id + "@t.local");
        return id;
    }

    private int register(UUID owner, String url) throws Exception {
        return mvc.perform(post("/api/v1/webhook-endpoints").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void internalUrlsCannotBeRegistered() throws Exception {
        UUID owner = user();
        for (String url : new String[] {
                "https://169.254.169.254/latest/meta-data/", "https://localhost:8080/actuator", "https://127.0.0.1/hook",
                "https://10.0.0.1/hook", "https://192.168.1.10/hook", "https://[::1]/hook", "http://example.com/hook"})
            assertEquals(400, register(owner, url), url);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoints WHERE user_id = ?", Integer.class, owner));
    }

    @Test
    void deliveryToAHostThatNowResolvesInternallyIsBlockedNotSent() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        internal.createContext("/", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        internal.start();
        try {
            // An endpoint whose host resolved publicly when registered, but now points inside (DNS rebinding).
            UUID owner = user(), endpoint = UUID.randomUUID();
            jdbc.update("INSERT INTO webhook_endpoints (id, user_id, url, secret_encrypted, active, created_at) VALUES (?, ?, ?, ?, TRUE, now())",
                    endpoint, owner, "https://127.0.0.1:" + internal.getAddress().getPort() + "/hook",
                    cipher.encrypt("whsec_test", endpoint.toString()));
            UUID from = LedgerTestData.openAccount(jdbc, "SSRF-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("10.00"), "CAD");
            jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, from);
            UUID to = LedgerTestData.openAccount(jdbc, "SSRF-" + UUID.randomUUID().toString().substring(0, 8), BigDecimal.ZERO, "CAD");
            banking.transfer(new TransferRequest(from, to, BigDecimal.ONE, "ssrf-" + UUID.randomUUID()));

            Instant t = Instant.now();
            for (int i = 0; i < 3; i++) { dispatcher.deliverDue(t); t = t.plus(Duration.ofHours(1)); }

            assertEquals(0, hits.get(), "No request may reach the internal address");
            var delivery = jdbc.queryForMap("SELECT status, last_error FROM webhook_deliveries WHERE endpoint_id = ?", endpoint);
            assertEquals("FAILED", delivery.get("status"));
            assertTrue(String.valueOf(delivery.get("last_error")).startsWith("Blocked:"), String.valueOf(delivery.get("last_error")));
        } finally {
            internal.stop(0);
        }
    }
}
