package com.smartbank.benchmark;

import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hot-account benchmark: how transfer throughput and latency change as a growing
 * share of transfers pays into one account (a busy merchant).
 *
 * Not part of the normal test run. Run with:
 *   mvn test -Dtest=HotAccountBenchmark -Dbenchmark=true
 * Optional: -Dbenchmark.threads=32 -Dbenchmark.transfers=4000 -Dbenchmark.runs=3
 *
 * Calls BankingService directly, so it measures locking, the ledger and the
 * database, not HTTP or JWT handling.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class HotAccountBenchmark {
    private static final int CUSTOMERS = 100;
    private static final double[] HOT_SHARES = {0.0, 0.25, 0.5, 0.75, 1.0};

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            // -Dbenchmark.syncCommit=off skips waiting for the WAL flush at commit. Diagnostic only:
            // it shows how much of the cost is disk sync. Never a production setting for money.
            .withCommand("postgres", "-c", "synchronous_commit=" + System.getProperty("benchmark.syncCommit", "on"));

    @Autowired BankingService banking;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize;

    private final int threads = Integer.getInteger("benchmark.threads", 32);
    private final int transfers = Integer.getInteger("benchmark.transfers", 4_000);
    private final int runs = Integer.getInteger("benchmark.runs", 3);

    record Result(double hotShare, double throughput, double p50Ms, double p95Ms, double p99Ms) { }

    @Test
    void hotAccountContention() throws Exception {
        List<String> rows = new ArrayList<>();
        for (double share : HOT_SHARES) {
            List<Result> results = new ArrayList<>();
            for (int run = 0; run < runs; run++) results.add(measure(share));
            // Report the run with the median throughput.
            results.sort(Comparator.comparingDouble(Result::throughput));
            Result r = results.get(results.size() / 2);
            rows.add("| %.0f%% | %.0f | %.1f | %.1f | %.1f |".formatted(
                    share * 100, r.throughput(), r.p50Ms(), r.p95Ms(), r.p99Ms()));
            System.out.printf("[benchmark] hot share %.0f%%: %s%n", share * 100, results);
        }
        String table = """
                Transfers per run: %d, client threads: %d, connection pool: %d, runs per row: %d (median shown)
                synchronous_commit: %s. Round trip (SELECT 1): %.2f ms. Single-row insert + commit: %.2f ms.
                One transfer with no concurrency: %.2f ms.

                | Transfers into hot account | Transfers/s | p50 ms | p95 ms | p99 ms |
                | --- | --- | --- | --- | --- |
                %s
                """.formatted(transfers, threads, poolSize, runs, System.getProperty("benchmark.syncCommit", "on"),
                roundTripMs(), commitMs(), sequentialTransferMs(), String.join("\n", rows));
        System.out.println("[benchmark]\n" + table);
        write(table);
    }

    private Result measure(double hotShare) throws Exception {
        LedgerTestData.truncateLedger(jdbc);
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'BENCH-%'");
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        BigDecimal opening = new BigDecimal("1000000.00");
        UUID merchant = LedgerTestData.openAccount(jdbc, "BENCH-MERCHANT", BigDecimal.ZERO, "CAD");
        List<UUID> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++)
            customers.add(LedgerTestData.openAccount(jdbc, "BENCH-%04d".formatted(i), opening, "CAD"));

        int warmup = Math.max(200, transfers / 10);
        run(warmup, hotShare, merchant, customers, "warm");
        long[] latencies = new long[transfers];
        long elapsed = run(transfers, hotShare, merchant, customers, "measure", latencies);

        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance after benchmark: " + report);
        Arrays.sort(latencies);
        return new Result(hotShare, transfers * 1e9 / elapsed,
                latencies[(int) (transfers * 0.50)] / 1e6,
                latencies[(int) (transfers * 0.95)] / 1e6,
                latencies[(int) (transfers * 0.99)] / 1e6);
    }

    private long run(int count, double hotShare, UUID merchant, List<UUID> customers, String tag) throws Exception {
        return run(count, hotShare, merchant, customers, tag, new long[count]);
    }

    /** Runs count transfers on the thread pool and returns wall-clock nanoseconds. */
    private long run(int count, double hotShare, UUID merchant, List<UUID> customers, String tag,
                     long[] latencies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();
        String prefix = tag + "-" + UUID.randomUUID() + "-";
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    UUID from = customers.get(r.nextInt(CUSTOMERS));
                    UUID to;
                    if (r.nextDouble() < hotShare) to = merchant;
                    else do { to = customers.get(r.nextInt(CUSTOMERS)); } while (to.equals(from));
                    BigDecimal amount = BigDecimal.valueOf(r.nextInt(100, 10_001), 2);
                    long start = System.nanoTime();
                    try {
                        banking.transfer(new TransferRequest(from, to, amount, prefix + n));
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                    latencies[n] = System.nanoTime() - start;
                    return null;
                }));
            }
            long start = System.nanoTime();
            gate.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.MINUTES);
            long elapsed = System.nanoTime() - start;
            assertTrue(failures.get() == 0, () -> failures.get() + " transfers failed during " + tag);
            return elapsed;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Mean time of one transfer when nothing else is running: the service time without contention. */
    private double sequentialTransferMs() {
        UUID a = LedgerTestData.openAccount(jdbc, "BENCH-SEQ-A-" + UUID.randomUUID().toString().substring(0, 6),
                new BigDecimal("1000000.00"), "CAD");
        UUID b = LedgerTestData.openAccount(jdbc, "BENCH-SEQ-B-" + UUID.randomUUID().toString().substring(0, 6),
                new BigDecimal("1000000.00"), "CAD");
        String prefix = "seq-" + UUID.randomUUID() + "-";
        for (int i = 0; i < 200; i++) banking.transfer(new TransferRequest(a, b, BigDecimal.ONE, prefix + "w" + i));
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) banking.transfer(new TransferRequest(a, b, BigDecimal.ONE, prefix + i));
        return (System.nanoTime() - start) / 500 / 1e6;
    }

    /** Mean time of a trivial query: the network and driver cost of one statement. */
    private double roundTripMs() {
        for (int i = 0; i < 100; i++) jdbc.queryForObject("SELECT 1", Integer.class);
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) jdbc.queryForObject("SELECT 1", Integer.class);
        return (System.nanoTime() - start) / 500 / 1e6;
    }

    /** Mean time of a one-row insert in its own transaction: one round trip plus the commit. */
    private double commitMs() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bench_probe (x INT)");
        for (int i = 0; i < 50; i++) jdbc.update("INSERT INTO bench_probe VALUES (1)");
        long start = System.nanoTime();
        for (int i = 0; i < 300; i++) jdbc.update("INSERT INTO bench_probe VALUES (1)");
        return (System.nanoTime() - start) / 300 / 1e6;
    }

    private static void write(String table) throws IOException {
        Path out = Path.of("target", "benchmark-results-sync-" + System.getProperty("benchmark.syncCommit", "on") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
    }
}
