package com.smartbank.transaction;

import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
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
import java.time.Duration;
import java.time.Instant;
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
    HoldService holds;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LedgerReconciliation reconciliation;

    private final List<UUID> accountIds = new ArrayList<>();

    @BeforeEach
    void createAccounts() {
        LedgerTestData.truncateLedger(jdbc);
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'CONC-%'");
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        accountIds.clear();
        for (int i = 0; i < ACCOUNTS; i++) {
            accountIds.add(LedgerTestData.openAccount(jdbc, "CONC-%05d".formatted(i), OPENING_BALANCE, "CAD"));
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
        assertEquals(succeeded.get(), (int) LedgerTestData.postedTransactionCount(jdbc),
                "Exactly one transaction row per successful transfer");
        assertLedgerClean();

        System.out.printf("[concurrency] %d transfers on %d threads in %d ms (%.0f/s): %d committed, %d rejected for insufficient funds, ledger reconciled%n",
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
        assertEquals(1, (int) LedgerTestData.postedTransactionCount(jdbc));
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
        assertEquals(1, (int) LedgerTestData.postedTransactionCount(jdbc));
        assertMoney("950.00", balance(from));
        assertLedgerClean();
        assertTrue(succeeded.get() >= 1, "The one posted transfer must be reported as a success");
    }

    @Test
    void identicalDepositsAndWithdrawalsFiredSimultaneouslyPostExactlyOnce() throws Exception {
        UUID account = accountIds.get(0);
        MoneyRequest depositRequest = new MoneyRequest(new BigDecimal("25.00"), "dup-deposit");
        MoneyRequest withdrawalRequest = new MoneyRequest(new BigDecimal("60.00"), "dup-withdrawal");
        Set<UUID> depositIds = ConcurrentHashMap.newKeySet();
        Set<UUID> withdrawalIds = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            work.add(() -> {
                try { depositIds.add(banking.deposit(account, depositRequest).id()); }
                catch (Throwable t) { failures.add(t); }
                return null;
            });
            work.add(() -> {
                try { withdrawalIds.add(banking.withdraw(account, withdrawalRequest).id()); }
                catch (Throwable t) { failures.add(t); }
                return null;
            });
        }
        runConcurrently(work);

        assertTrue(failures.isEmpty(), () -> "Retries failed: " + failures.stream().limit(5).toList());
        assertEquals(1, depositIds.size(), "All deposit retries must return the same transaction");
        assertEquals(1, withdrawalIds.size(), "All withdrawal retries must return the same transaction");
        assertEquals(2, (int) LedgerTestData.postedTransactionCount(jdbc));
        assertMoney("965.00", balance(account));
        assertLedgerClean();
    }

    @Test
    void racingPostAndVoidOfTheSameHoldResolveItExactlyOnce() throws Exception {
        UUID payer = accountIds.get(0), payee = accountIds.get(1);
        List<UUID> holdIds = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            holdIds.add(holds.create(new CreateHoldRequest(payer, payee, new BigDecimal("10.00"), "race-" + i, null)).id());
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> work = new ArrayList<>();
        for (UUID id : holdIds) {
            for (int j = 0; j < 10; j++) {
                work.add(() -> { try { holds.post(id, null); } catch (BankingException ignored) { } catch (Throwable t) { unexpected.add(t); } return null; });
                work.add(() -> { try { holds.voidHold(id); } catch (BankingException ignored) { } catch (Throwable t) { unexpected.add(t); } return null; });
            }
        }
        Collections.shuffle(work);
        runConcurrently(work);

        assertTrue(unexpected.isEmpty(), () -> "Unexpected failures: " + unexpected.stream().limit(5).toList());
        int posted = 0;
        for (UUID id : holdIds) {
            HoldStatus status = holds.get(id).status();
            assertTrue(status == HoldStatus.POSTED || status == HoldStatus.VOIDED, "Hold must be final: " + status);
            if (status == HoldStatus.POSTED) posted++;
        }
        assertEquals(posted, (int) LedgerTestData.postedTransactionCount(jdbc), "One transfer per posted hold, none for voided");
        assertMoney(new BigDecimal("1000.00").subtract(BigDecimal.TEN.multiply(BigDecimal.valueOf(posted))).toPlainString(), balance(payer));
        assertLedgerClean();
    }

    @Test
    void randomMixOfHoldsTransfersAndExpiryKeepsTheBooksExact() throws Exception {
        BigDecimal totalBefore = totalBalance();
        List<UUID> holdIds = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        Instant farFuture = Instant.now().plus(Duration.ofDays(60));
        int operations = 4_000;

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            int n = i;
            work.add(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                UUID a = accountIds.get(r.nextInt(ACCOUNTS));
                UUID b;
                do { b = accountIds.get(r.nextInt(ACCOUNTS)); } while (b.equals(a));
                BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 20_001), 2);
                int op = holdIds.isEmpty() ? 0 : r.nextInt(5);
                String name = switch (op) { case 0 -> "hold"; case 1 -> "post"; case 2 -> "void"; case 3 -> "expire"; default -> "transfer"; };
                try {
                    switch (op) {
                        case 0 -> holdIds.add(holds.create(new CreateHoldRequest(a, b, amount, "mix-" + n, null)).id());
                        case 1 -> {
                            UUID id = holdIds.get(r.nextInt(holdIds.size()));
                            // Half full posts, half partial (possibly above the held amount, which must be rejected).
                            holds.post(id, r.nextBoolean() ? null : amount);
                        }
                        case 2 -> holds.voidHold(holdIds.get(r.nextInt(holdIds.size())));
                        case 3 -> holds.expire(holdIds.get(r.nextInt(holdIds.size())), farFuture);
                        default -> banking.transfer(new TransferRequest(a, b, amount, "mix-t-" + n));
                    }
                    outcomes.computeIfAbsent(name + " ok", k -> new AtomicInteger()).incrementAndGet();
                } catch (BankingException e) {
                    outcomes.computeIfAbsent(name + " rejected", k -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        long elapsedMs = runConcurrently(work);

        assertTrue(unexpected.isEmpty(), () -> "Unexpected failures (deadlocks, constraint violations, etc.): "
                + unexpected.stream().limit(5).map(Throwable::toString).toList());
        assertEquals(0, totalBefore.compareTo(totalBalance()), "Holds never create or destroy money");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE account_number LIKE 'CONC-%' AND balance - pending_debits < 0", Integer.class));
        assertLedgerClean();
        System.out.printf("[concurrency] %d mixed hold/transfer operations on %d threads in %d ms: %s, books reconciled%n",
                operations, THREADS, elapsedMs, new TreeMap<>(outcomes));
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

    /** The ledger balances and every cached balance equals the sum of its account's entries. */
    private void assertLedgerClean() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Ledger out of balance: " + report);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
