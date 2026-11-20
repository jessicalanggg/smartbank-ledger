package com.smartbank.transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariant tests for concurrent banking operations against a real PostgreSQL
 * instance. Each test releases many threads at once from a shared start gate so
 * requests genuinely overlap inside the database.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
class ConcurrencyIntegrationTest {
    private static final int ACCOUNTS = 10;
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("1000.00");
    private static final int TRANSFERS = 5_000;
    private static final int THREADS = 32;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    BankingService banking;
    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> accountIds = new ArrayList<>();

    @BeforeEach
    void createAccounts() {
        jdbc.update("DELETE FROM banking_transactions");
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'CONC-%'");
        accountIds.clear();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO accounts (id, account_number, owner_name, account_type, balance, currency)
                    VALUES (?, ?, 'Concurrency Test', 'CHECKING', ?, 'CAD')""",
                    id, "CONC-%05d".formatted(i), OPENING_BALANCE);
            accountIds.add(id);
        }
    }

    @Test
    void randomConcurrentTransfersConserveMoneyAndReconcileWithLedger() throws Exception {
        BigDecimal totalBefore = totalBalance();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int n = i;
            work.add(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                UUID from = accountIds.get(r.nextInt(ACCOUNTS));
                UUID to;
                do { to = accountIds.get(r.nextInt(ACCOUNTS)); } while (to.equals(from));
                // Amounts up to 300.00 make insufficient-funds rejections common, which
                // exercises the rollback path under contention as well.
                BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 30_001), 2);
                try {
                    banking.transfer(new TransferRequest(from, to, amount, "conc-" + n));
                    succeeded.incrementAndGet();
                } catch (BankingException e) {
                    if ("Insufficient funds".equals(e.getMessage())) insufficientFunds.incrementAndGet();
                    else unexpected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        long elapsedMs = runConcurrently(work);

        assertTrue(unexpected.isEmpty(), () -> "Unexpected failures (deadlocks, lock timeouts, etc.): "
                + unexpected.stream().limit(5).map(Throwable::toString).toList());
        assertEquals(TRANSFERS, succeeded.get() + insufficientFunds.get());
        assertEquals(0, totalBefore.compareTo(totalBalance()), "Total money must be conserved");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE account_number LIKE 'CONC-%' AND balance < 0", Integer.class));
        assertEquals(succeeded.get(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM banking_transactions", Integer.class),
                "Exactly one transaction row per successful transfer");
        assertEquals(0, ledgerMismatches(), "Every balance must equal opening balance + credits - debits");

        System.out.printf("[concurrency] %d transfers on %d threads in %d ms (%.0f/s): %d committed, %d rejected for insufficient funds, 0 discrepancies%n",
                TRANSFERS, THREADS, elapsedMs, TRANSFERS * 1000.0 / elapsedMs, succeeded.get(), insufficientFunds.get());
    }

    @Test
    void identicalRequestsFiredSimultaneouslyPostExactlyOnce() throws Exception {
        UUID from = accountIds.get(0), to = accountIds.get(1);
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("100.00"), "dup-same");
        Set<UUID> returnedIds = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            work.add(() -> {
                try { returnedIds.add(banking.transfer(request).id()); }
                catch (Throwable t) { failures.add(t); }
                return null;
            });
        }
        runConcurrently(work);

        // Every retry must be answered with the original transaction, not an error.
        assertTrue(failures.isEmpty(), () -> "Retries failed: " + failures.stream().limit(5).toList());
        assertEquals(1, returnedIds.size(), "All retries must return the same transaction");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions", Integer.class));
        assertMoney("900.00", balance(from));
        assertMoney("1100.00", balance(to));
    }

    @Test
    void conflictingRequestsWithSameKeyFiredSimultaneouslyPostAtMostOnce() throws Exception {
        // Same key but different destinations, so the requests take different locks
        // and are not serialised by account locking. Only the unique constraint on
        // request_key stands between them and a double posting.
        UUID from = accountIds.get(0);
        AtomicInteger succeeded = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 1; i < ACCOUNTS; i++) {
            UUID to = accountIds.get(i);
            for (int j = 0; j < 10; j++) {
                work.add(() -> {
                    try {
                        banking.transfer(new TransferRequest(from, to, new BigDecimal("50.00"), "dup-conflict"));
                        succeeded.incrementAndGet();
                    } catch (BankingException | DataIntegrityViolationException expected) {
                        // Rejected as a conflicting reuse of the key: the correct outcome.
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                    return null;
                });
            }
        }
        runConcurrently(work);

        assertTrue(unexpected.isEmpty(), () -> "Unexpected failures: " + unexpected.stream().limit(5).toList());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions", Integer.class));
        assertMoney("950.00", balance(from));
        assertEquals(0, ledgerMismatches());
        assertTrue(succeeded.get() >= 1, "The one posted transfer must be reported as a success");
    }

    /** Starts every task at the same instant and returns the wall-clock time taken. */
    private long runConcurrently(List<Callable<Void>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : work) {
                futures.add(pool.submit(() -> { gate.await(); return task.call(); }));
            }
            long start = System.nanoTime();
            gate.countDown();
            for (Future<Void> f : futures) f.get(2, TimeUnit.MINUTES);
            return (System.nanoTime() - start) / 1_000_000;
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal totalBalance() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE account_number LIKE 'CONC-%'", BigDecimal.class);
    }

    private BigDecimal balance(UUID id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    /** Accounts whose stored balance disagrees with what the transaction log implies. */
    private int ledgerMismatches() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a
                WHERE a.account_number LIKE 'CONC-%'
                  AND a.balance <> ?
                      + COALESCE((SELECT SUM(amount) FROM banking_transactions t WHERE t.destination_account_id = a.id), 0)
                      - COALESCE((SELECT SUM(amount) FROM banking_transactions t WHERE t.source_account_id = a.id), 0)""",
                Integer.class, OPENING_BALANCE);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
