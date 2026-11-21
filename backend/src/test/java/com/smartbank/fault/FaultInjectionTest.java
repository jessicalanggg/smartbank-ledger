package com.smartbank.fault;

import com.github.dockerjava.api.DockerClient;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.BankingException;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.TransactionResponse;
import com.smartbank.transaction.TransferRequest;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault injection under load. The application reaches PostgreSQL only through
 * Toxiproxy, so connections can be cut on command, and the PostgreSQL container
 * can be killed with SIGKILL and restarted (it then recovers from its WAL).
 *
 * Client threads send transfers and, like a well-behaved API client, retry any
 * request that fails with a connection error using the same request key, until it
 * succeeds or is rejected for a business reason. After the faults stop:
 *
 * - every transfer a client was told succeeded exists, with the id it was given
 *   (acknowledged commits survive a database crash);
 * - no request key posted more than once;
 * - total money is unchanged and the ledger reconciles;
 * - no request was left unresolved.
 *
 * It also counts commits whose acknowledgement was lost (the attempt failed from
 * the client's point of view but had committed), which a retry then replayed.
 */
@ActiveProfiles("test")
@SpringBootTest
class FaultInjectionTest {
    private static final int ACCOUNTS = 20;
    private static final BigDecimal OPENING = new BigDecimal("1000.00");
    private static final int TRANSFERS = 1_500;
    private static final int THREADS = 16;
    private static final Duration GIVE_UP_AFTER = Duration.ofSeconds(90);

    static final Network network = Network.newNetwork();
    // fsync and synchronous_commit on: an acknowledged commit must be durable.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(network).withNetworkAliases("pg")
            .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(network);
    static final Proxy proxy;

    static {
        postgres.start();
        toxiproxy.start();
        try {
            proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "pg:5432");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String proxiedJdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=30".formatted(
                toxiproxy.getHost(), toxiproxy.getMappedPort(8666), postgres.getDatabaseName());
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", FaultInjectionTest::proxiedJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Fail fast while the database is unreachable, so clients retry instead of hanging.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
    }

    @Autowired BankingService banking;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cut(false);
        awaitDatabase();
        LedgerTestData.truncateLedger(jdbc);
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'FAULT-%'");
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        accounts.clear();
        for (int i = 0; i < ACCOUNTS; i++)
            accounts.add(LedgerTestData.openAccount(jdbc, "FAULT-%03d".formatted(i), OPENING, "CAD"));
    }

    @Test
    void connectionsCutRepeatedlyUnderLoad() throws Exception {
        // Cut every connection at 10%, 20%, ... 80% of the load, for 0.2-0.6 s each time.
        Outcome outcome = runUnderFaults(progress -> {
            Random r = new Random();
            for (int i = 1; i <= 8; i++) {
                awaitProgress(progress, i / 10.0);
                cut(true);
                sleep(200 + r.nextInt(400));
                cut(false);
            }
        });
        outcome.verify("network cuts");
        assertTrue(outcome.connectionErrors > 0, "The faults must actually have interrupted requests");
    }

    @Test
    void databaseKilledAndRestartedUnderLoad() throws Exception {
        // SIGKILL PostgreSQL at 25% and again at 60% of the load.
        Outcome outcome = runUnderFaults(progress -> {
            awaitProgress(progress, 0.25);
            killAndRestartDatabase();
            awaitProgress(progress, 0.60);
            killAndRestartDatabase();
        });
        outcome.verify("database crashes");
        assertTrue(outcome.connectionErrors > 0, "The crashes must actually have interrupted requests");
        // PostgreSQL's own log confirms each restart was crash recovery from the WAL, not a clean start.
        String logs = postgres.getLogs();
        long recoveries = logs.lines().filter(l -> l.contains("automatic recovery in progress")).count();
        assertTrue(recoveries >= 2, "Expected two crash recoveries in the PostgreSQL log, found " + recoveries);
        System.out.printf("[fault] PostgreSQL log shows %d crash recoveries (\"automatic recovery in progress\")%n", recoveries);
    }

    // ---- Load with retries -----------------------------------------------------

    enum Status { SUCCEEDED, REJECTED, GAVE_UP }

    static final class Request {
        final String key;
        final UUID from, to;
        final BigDecimal amount;
        Status status;
        TransactionResponse response;
        int connectionErrors;
        Instant lastErrorAt;
        Request(String key, UUID from, UUID to, BigDecimal amount) {
            this.key = key; this.from = from; this.to = to; this.amount = amount;
        }
    }

    final class Outcome {
        final List<Request> requests;
        final int connectionErrors;
        final long elapsedMs;
        Outcome(List<Request> requests, long elapsedMs) {
            this.requests = requests;
            this.elapsedMs = elapsedMs;
            this.connectionErrors = requests.stream().mapToInt(r -> r.connectionErrors).sum();
        }

        void verify(String scenario) {
            Map<Status, Long> byStatus = new EnumMap<>(Status.class);
            requests.forEach(r -> byStatus.merge(r.status, 1L, Long::sum));
            assertEquals(0, byStatus.getOrDefault(Status.GAVE_UP, 0L), "Every request must resolve once the faults stop");

            // Acknowledged commits are durable and match what the client was told.
            int lostAcks = 0, rejectedButCommitted = 0;
            for (Request r : requests) {
                List<UUID> rows = jdbc.queryForList(
                        "SELECT id FROM banking_transactions WHERE request_key = ?", UUID.class, r.key);
                assertTrue(rows.size() <= 1, "Request key posted more than once: " + r.key);
                if (r.status == Status.SUCCEEDED) {
                    assertEquals(List.of(r.response.id()), rows,
                            "Transfer acknowledged to the client is missing or different after recovery: " + r.key);
                    // Created before the last failed attempt ended: that attempt had committed.
                    if (r.lastErrorAt != null && r.response.createdAt().isBefore(r.lastErrorAt)) lostAcks++;
                } else if (!rows.isEmpty()) {
                    // A rejection can follow an attempt whose commit outcome the client never learned.
                    assertTrue(r.connectionErrors > 0, "Rejected request was committed without any fault: " + r.key);
                    rejectedButCommitted++;
                }
            }

            BigDecimal total = jdbc.queryForObject(
                    "SELECT SUM(balance) FROM accounts WHERE account_number LIKE 'FAULT-%'", BigDecimal.class);
            assertEquals(0, OPENING.multiply(BigDecimal.valueOf(ACCOUNTS)).compareTo(total), "Money was created or lost");
            var report = reconciliation.run();
            assertTrue(report.isClean(), () -> "Books out of balance after " + scenario + ": " + report);

            System.out.printf("[fault] %s: %d transfers in %d ms, %s; %d connection errors retried; "
                            + "%d lost acknowledgements recovered by replay; %d rejections after an uncertain commit; books reconciled%n",
                    scenario, requests.size(), elapsedMs, byStatus, connectionErrors, lostAcks, rejectedButCommitted);
        }
    }

    /** Runs the load while {@code faults} injects failures; it receives the count of resolved requests. */
    private Outcome runUnderFaults(Consumer<IntSupplier> faults) throws Exception {
        AtomicInteger resolved = new AtomicInteger();
        List<Request> requests = new ArrayList<>();
        Random r = new Random();
        for (int i = 0; i < TRANSFERS; i++) {
            int a = r.nextInt(ACCOUNTS), b;
            do { b = r.nextInt(ACCOUNTS); } while (b == a);
            requests.add(new Request("fault-" + UUID.randomUUID(), accounts.get(a), accounts.get(b),
                    BigDecimal.valueOf(r.nextInt(100, 5_001), 2)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
        try {
            long start = System.nanoTime();
            Future<?> injector = pool.submit(() -> { faults.accept(resolved::get); return null; });
            List<Future<?>> clients = new ArrayList<>();
            for (Request request : requests)
                clients.add(pool.submit(() -> { send(request); resolved.incrementAndGet(); return null; }));
            injector.get(5, TimeUnit.MINUTES);
            cut(false);
            awaitDatabase();
            for (Future<?> f : clients) f.get(5, TimeUnit.MINUTES);
            return new Outcome(requests, (System.nanoTime() - start) / 1_000_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Sends one transfer, retrying with the same key on anything but a business rejection. */
    private void send(Request request) {
        Instant deadline = Instant.now().plus(GIVE_UP_AFTER);
        while (true) {
            try {
                request.response = banking.transfer(new TransferRequest(request.from, request.to, request.amount, request.key));
                request.status = Status.SUCCEEDED;
                return;
            } catch (BankingException e) {
                request.status = Status.REJECTED;
                return;
            } catch (RuntimeException e) {
                request.connectionErrors++;
                request.lastErrorAt = Instant.now();
                if (Instant.now().isAfter(deadline)) {
                    request.status = Status.GAVE_UP;
                    return;
                }
                sleep(50 + ThreadLocalRandom.current().nextInt(150));
            }
        }
    }

    // ---- Faults ------------------------------------------------------------------

    private static void awaitProgress(IntSupplier resolved, double fraction) {
        while (resolved.getAsInt() < TRANSFERS * fraction) sleep(5);
    }

    /** Disabling the proxy drops every open connection and refuses new ones. */
    private static void cut(boolean cut) {
        try {
            if (cut) proxy.disable();
            else proxy.enable();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SIGKILL the PostgreSQL server (no clean shutdown), then start it again. */
    private static void killAndRestartDatabase() {
        DockerClient docker = DockerClientFactory.instance().client();
        docker.killContainerCmd(postgres.getContainerId()).withSignal("KILL").exec();
        sleep(500);
        docker.startContainerCmd(postgres.getContainerId()).exec();
        awaitDatabase();
    }

    /** Waits until PostgreSQL accepts connections through the proxy again. */
    private static void awaitDatabase() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (true) {
            try (var c = DriverManager.getConnection(proxiedJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var s = c.createStatement()) {
                s.execute("SELECT 1");
                return;
            } catch (Exception e) {
                if (Instant.now().isAfter(deadline)) throw new IllegalStateException("Database did not come back", e);
                sleep(200);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
