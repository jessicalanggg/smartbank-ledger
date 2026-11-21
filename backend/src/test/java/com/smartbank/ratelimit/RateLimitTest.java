package com.smartbank.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Rate limits through the real HTTP stack and PostgreSQL, with small limits for this test. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {
        "smartbank.rate-limits.login-per-email.capacity=3",
        "smartbank.rate-limits.login-per-email.refill-every=1h",
        "smartbank.rate-limits.auth-per-ip.capacity=10",
        "smartbank.rate-limits.auth-per-ip.refill-every=1h",
        "smartbank.rate-limits.register-per-ip.capacity=2",
        "smartbank.rate-limits.register-per-ip.refill-every=1h",
        "smartbank.rate-limits.anonymous-per-ip.capacity=5",
        "smartbank.rate-limits.anonymous-per-ip.refill-every=1h",
        "smartbank.rate-limits.api-per-user.capacity=8",
        "smartbank.rate-limits.api-per-user.refill-every=300ms",
        "smartbank.rate-limits.writes-per-user.capacity=3",
        "smartbank.rate-limits.writes-per-user.refill-every=1h"})
@AutoConfigureMockMvc
class RateLimitTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired RateLimiter limiter;

    @BeforeEach
    void emptyBuckets() {
        jdbc.update("DELETE FROM rate_limit_buckets");
    }

    private static String ip() {
        return "10.%d.%d.%d".formatted(ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256), ThreadLocalRandom.current().nextInt(1, 255));
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    private String registeredEmail(String password) {
        String email = "rl-" + UUID.randomUUID() + "@t.local";
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, ?, 'CUSTOMER')",
                UUID.randomUUID(), email, encoder.encode(password));
        return email;
    }

    private MockHttpServletResponse login(String email, String password, String ip) throws Exception {
        return mvc.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn().getResponse();
    }

    private MockHttpServletResponse apiGet(UUID user) throws Exception {
        return mvc.perform(get("/api/v1/accounts").with(jwt().jwt(j -> j.subject(user.toString()))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse apiPost(UUID user) throws Exception {
        return mvc.perform(post("/api/v1/webhook-endpoints").with(jwt().jwt(j -> j.subject(user.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://invalid\"}")).andReturn().getResponse();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", id, id + "@t.local");
        return id;
    }

    @Test
    void failedLoginsLockTheAccountButSuccessesDoNotCount() throws Exception {
        String email = registeredEmail("correct-horse-battery");

        // Successful logins give their token back: any number is fine.
        for (int i = 0; i < 6; i++) assertEquals(200, login(email, "correct-horse-battery", ip()).getStatus());

        // Three wrong guesses, from different IPs, use up the account's budget.
        for (int i = 0; i < 3; i++) assertEquals(401, login(email, "guess-" + i, ip()).getStatus());
        var locked = login(email, "correct-horse-battery", ip());
        assertEquals(429, locked.getStatus(), "Even the right password waits once the budget is spent");
        assertNotNull(locked.getHeader("Retry-After"));
        assertTrue(Long.parseLong(locked.getHeader("Retry-After")) > 0);
        assertEquals("application/problem+json", locked.getContentType());

        // Other accounts are unaffected.
        assertEquals(200, login(registeredEmail("other-password-123"), "other-password-123", ip()).getStatus());
    }

    @Test
    void unknownEmailsAreLimitedLikeRealOnes() throws Exception {
        String ghost = "nobody-" + UUID.randomUUID() + "@t.local";
        for (int i = 0; i < 3; i++) assertEquals(401, login(ghost, "x", ip()).getStatus());
        assertEquals(429, login(ghost, "x", ip()).getStatus(), "Same response as for a real account");
    }

    @Test
    void oneAddressCannotSprayManyAccounts() throws Exception {
        String attacker = ip();
        for (int i = 0; i < 10; i++)
            assertEquals(401, login("victim-" + i + "@t.local", "x", attacker).getStatus());
        assertEquals(429, login("victim-10@t.local", "x", attacker).getStatus());
        assertEquals(401, login("victim-10@t.local", "x", ip()).getStatus(), "Other addresses are unaffected");
    }

    @Test
    void registrationsArePerAddress() throws Exception {
        String address = ip();
        for (int i = 0; i < 2; i++)
            assertEquals(201, mvc.perform(from(post("/api/v1/auth/register"), address).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"new-%s@t.local\",\"password\":\"long-enough-password\"}".formatted(UUID.randomUUID())))
                    .andReturn().getResponse().getStatus());
        assertEquals(429, mvc.perform(from(post("/api/v1/auth/register"), address).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new-%s@t.local\",\"password\":\"long-enough-password\"}".formatted(UUID.randomUUID())))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void apiLimitIsPerUserAndRefills() throws Exception {
        UUID alice = user(), bob = user();
        for (int i = 0; i < 8; i++) assertEquals(200, apiGet(alice).getStatus());
        var refused = apiGet(alice);
        assertEquals(429, refused.getStatus());
        assertEquals("1", refused.getHeader("Retry-After"), "One token refills in 300 ms, rounded up to 1 s");
        assertEquals(200, apiGet(bob).getStatus(), "Another user has their own budget");

        Thread.sleep(700);  // two tokens refill
        assertEquals(200, apiGet(alice).getStatus());
        assertEquals(200, apiGet(alice).getStatus());
        assertEquals(429, apiGet(alice).getStatus());
    }

    @Test
    void writesHaveTheirOwnStricterLimit() throws Exception {
        UUID alice = user();
        for (int i = 0; i < 3; i++) assertEquals(400, apiPost(alice).getStatus(), "Reaches the endpoint (invalid URL)");
        assertEquals(429, apiPost(alice).getStatus());
        assertEquals(200, apiGet(alice).getStatus(), "Reads still work");
    }

    @Test
    void requestsWithoutAValidTokenAreLimitedPerAddress() throws Exception {
        String address = ip();
        for (int i = 0; i < 5; i++)
            assertEquals(401, mvc.perform(from(get("/api/v1/accounts"), address)).andReturn().getResponse().getStatus());
        assertEquals(429, mvc.perform(from(get("/api/v1/accounts"), address)).andReturn().getResponse().getStatus());
    }

    @Test
    void concurrentRequestsNeverTakeMoreTokensThanExist() throws Exception {
        RateLimiter.Limit limit = new RateLimiter.Limit(25, Duration.ofHours(1));
        String key = "race-" + UUID.randomUUID();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++)
                futures.add(pool.submit(() -> {
                    gate.await();
                    if (limiter.tryAcquire(key, limit).allowed()) allowed.incrementAndGet();
                    return null;
                }));
            gate.countDown();
            for (var f : futures) f.get(1, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(25, allowed.get(), "Exactly the capacity, including the first request that creates the bucket");
    }

    @Test
    void instancesShareOneBudget() {
        // Two app instances: separate limiter objects, one database.
        RateLimiter first = new RateLimiter(jdbc, Clock.systemUTC()), second = new RateLimiter(jdbc, Clock.systemUTC());
        RateLimiter.Limit limit = new RateLimiter.Limit(4, Duration.ofHours(1));
        String key = "shared-" + UUID.randomUUID();
        int allowed = 0;
        for (int i = 0; i < 10; i++)
            if ((i % 2 == 0 ? first : second).tryAcquire(key, limit).allowed()) allowed++;
        assertEquals(4, allowed);
    }

    @Test
    void idleBucketsArePruned() {
        limiter.tryAcquire("old-" + UUID.randomUUID(), new RateLimiter.Limit(5, Duration.ofSeconds(1)));
        jdbc.update("UPDATE rate_limit_buckets SET updated_at = updated_at - INTERVAL '2 days'");
        assertTrue(limiter.pruneIdle(Instant.now().minus(Duration.ofDays(1))) >= 1);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets", Integer.class));
    }
}
