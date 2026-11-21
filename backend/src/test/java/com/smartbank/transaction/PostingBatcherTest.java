package com.smartbank.transaction;

import com.smartbank.account.AccountNotFoundException;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group commit, batch by batch, for transfers, deposits and withdrawals. Tests hand the batcher an explicit batch, so the
 * order of transfers inside it is known.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
class PostingBatcherTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired PostingBatcher batcher;
    @Autowired BankingService banking;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReconciliation reconciliation;

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID account(String balance) {
        return LedgerTestData.openAccount(jdbc, "BATCH-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
    }

    private static PostingBatcher.Pending pending(UUID from, UUID to, String amount, String key) {
        return new PostingBatcher.Pending(PostingBatcher.Posting.transfer(new TransferRequest(from, to, new BigDecimal(amount), key)),
                new CompletableFuture<>());
    }

    private static PostingBatcher.Pending deposit(UUID account, String amount, String key) {
        return new PostingBatcher.Pending(PostingBatcher.Posting.deposit(account, new MoneyRequest(new BigDecimal(amount), key)),
                new CompletableFuture<>());
    }

    private static PostingBatcher.Pending withdrawal(UUID account, String amount, String key) {
        return new PostingBatcher.Pending(PostingBatcher.Posting.withdrawal(account, new MoneyRequest(new BigDecimal(amount), key)),
                new CompletableFuture<>());
    }

    private static String key() {
        return "batch-" + UUID.randomUUID();
    }

    /** Result of one transfer in a processed batch: its response, or the exception class name. */
    private static Object outcome(PostingBatcher.Pending p) {
        assertTrue(p.result().isDone(), "Every caller must be answered");
        try {
            return p.result().getNow(null);
        } catch (CompletionException e) {
            return e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        }
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void oneRejectedTransferDoesNotFailTheBatch() {
        UUID a = account("100.00"), b = account("0.00"), poor = account("5.00");
        var ok1 = pending(a, b, "30.00", key());
        var tooMuch = pending(poor, b, "50.00", key());
        var unknown = pending(UUID.randomUUID(), b, "1.00", key());
        var ok2 = pending(a, b, "20.00", key());

        batcher.process(List.of(ok1, tooMuch, unknown, ok2));

        assertInstanceOf(TransactionResponse.class, outcome(ok1));
        assertEquals("BankingException: Insufficient funds", outcome(tooMuch));
        assertTrue(String.valueOf(outcome(unknown)).startsWith(AccountNotFoundException.class.getSimpleName()));
        assertInstanceOf(TransactionResponse.class, outcome(ok2));
        assertMoney("50.00", balance(a));
        assertMoney("50.00", balance(b));
        assertMoney("5.00", balance(poor));
    }

    @Test
    void transfersAreDecidedInArrivalOrder() {
        UUID a = account("0.00"), b = account("100.00"), c = account("0.00");
        // a can pay c only because b paid a earlier in the same batch.
        var bPaysA = pending(b, a, "60.00", key());
        var aPaysC = pending(a, c, "50.00", key());
        // In the other order, the first one fails: a has nothing yet.
        var cPaysB = pending(c, b, "70.00", key());
        var bPaysC = pending(b, c, "40.00", key());

        batcher.process(List.of(bPaysA, aPaysC, cPaysB, bPaysC));

        assertInstanceOf(TransactionResponse.class, outcome(bPaysA));
        assertInstanceOf(TransactionResponse.class, outcome(aPaysC));
        assertEquals("BankingException: Insufficient funds", outcome(cPaysB), "c had only 50 at that point");
        assertInstanceOf(TransactionResponse.class, outcome(bPaysC));
        assertMoney("10.00", balance(a));
        assertMoney("0.00", balance(b));
        assertMoney("90.00", balance(c));
    }

    @Test
    void duplicateKeysInsideOneBatchPostOnce() {
        UUID a = account("100.00"), b = account("0.00");
        String k = key();
        var first = pending(a, b, "10.00", k);
        var sameAgain = pending(a, b, "10.00", k);
        var conflicting = pending(a, b, "11.00", k);

        batcher.process(List.of(first, sameAgain, conflicting));

        TransactionResponse posted = (TransactionResponse) outcome(first);
        assertEquals(posted, outcome(sameAgain));
        assertEquals("BankingException: Request key was already used for a different operation", outcome(conflicting));
        assertMoney("90.00", balance(a));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions WHERE request_key = ?", Integer.class, k));
    }

    @Test
    void committedKeysAreReplayedInsideABatch() {
        UUID a = account("100.00"), b = account("0.00");
        String k = key();
        TransactionResponse earlier = banking.transferDirect(new TransferRequest(a, b, new BigDecimal("10.00"), k));
        var retry = pending(a, b, "10.00", k);
        var fresh = pending(a, b, "5.00", key());

        batcher.process(List.of(retry, fresh));

        assertEquals(earlier, outcome(retry));
        assertInstanceOf(TransactionResponse.class, outcome(fresh));
        assertMoney("85.00", balance(a));
    }

    @Test
    /**
     * The same key in flight in a batch and on the direct path at once. The batch
     * locks accounts before inserting keys and the direct path does the reverse, so
     * PostgreSQL may report a deadlock and abort either side: the batch then settles
     * each transfer on its own, or the direct transfer retries. Whichever wins, both
     * callers must get the same transaction and the key must post once.
     */
    void sameKeyInABatchAndOnTheDirectPathPostsOnce() throws Exception {
        UUID a = account("100.00"), b = account("0.00");
        String contested = key();
        var p1 = pending(a, b, "10.00", contested);
        var p2 = pending(a, b, "20.00", key());

        // Hold a lock on b so the batch waits; meanwhile a direct transfer with the contested key starts.
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var blocker = CompletableFuture.runAsync(() -> jdbc.execute((java.sql.Connection c) -> {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR NO KEY UPDATE")) {
                s.setObject(1, b);
                s.execute();
                locked.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            c.rollback();
            c.setAutoCommit(true);
            return null;
        }));
        locked.await(10, TimeUnit.SECONDS);
        var batchDone = CompletableFuture.runAsync(() -> batcher.process(List.of(p1, p2)));
        Thread.sleep(300);  // the batch has read the keys and is waiting for the account lock
        var direct = CompletableFuture.supplyAsync(() ->
                banking.transferDirect(new TransferRequest(a, b, new BigDecimal("10.00"), contested)));
        Thread.sleep(300);
        release.countDown();
        blocker.get(30, TimeUnit.SECONDS);
        TransactionResponse directResult = direct.get(30, TimeUnit.SECONDS);
        batchDone.get(30, TimeUnit.SECONDS);

        assertEquals(directResult, outcome(p1), "Both callers get the same transaction");
        assertInstanceOf(TransactionResponse.class, outcome(p2), "The unrelated transfer in the batch still posts");
        assertMoney("70.00", balance(a));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM banking_transactions WHERE request_key = ?",
                Integer.class, contested));
    }

    @Test
    void depositsAndWithdrawalsShareBatchesWithTransfers() {
        UUID a = account("0.00"), b = account("10.00");
        var depositA = deposit(a, "50.00", key());
        var withdrawA = withdrawal(a, "30.00", key());       // funded by the deposit just before it
        var overdraw = withdrawal(b, "10.01", key());        // more than b has
        var aPaysB = pending(a, b, "20.00", key());          // uses the rest of a's deposit
        var withdrawB = withdrawal(b, "30.00", key());       // b now has 30.00

        batcher.process(List.of(depositA, withdrawA, overdraw, aPaysB, withdrawB));

        assertEquals("DEPOSIT", ((TransactionResponse) outcome(depositA)).type().name());
        assertEquals("WITHDRAWAL", ((TransactionResponse) outcome(withdrawA)).type().name());
        assertEquals("BankingException: Insufficient funds", outcome(overdraw));
        assertInstanceOf(TransactionResponse.class, outcome(aPaysB));
        assertInstanceOf(TransactionResponse.class, outcome(withdrawB));
        assertMoney("0.00", balance(a));
        assertMoney("0.00", balance(b));
    }

    @Test
    void keysAreSharedAcrossPostingTypesInABatch() {
        UUID a = account("100.00");
        String k = key();
        var depositFirst = deposit(a, "10.00", k);
        var withdrawalSameKey = withdrawal(a, "10.00", k);
        var depositRetry = deposit(a, "10.00", k);

        batcher.process(List.of(depositFirst, withdrawalSameKey, depositRetry));

        TransactionResponse posted = (TransactionResponse) outcome(depositFirst);
        assertEquals("BankingException: Request key was already used for a different operation", outcome(withdrawalSameKey));
        assertEquals(posted, outcome(depositRetry));
        assertMoney("110.00", balance(a));
    }

    @Test
    void committedDepositsAreReplayedAndUnknownAccountsRejectedAlone() {
        UUID a = account("100.00");
        String k = key();
        TransactionResponse earlier = banking.depositDirect(a, new MoneyRequest(new BigDecimal("5.00"), k));
        var retry = deposit(a, "5.00", k);
        var unknown = deposit(UUID.randomUUID(), "5.00", key());
        var fresh = withdrawal(a, "15.00", key());

        batcher.process(List.of(retry, unknown, fresh));

        assertEquals(earlier, outcome(retry));
        assertTrue(String.valueOf(outcome(unknown)).startsWith(AccountNotFoundException.class.getSimpleName()));
        assertInstanceOf(TransactionResponse.class, outcome(fresh));
        assertMoney("90.00", balance(a));
    }

    @Test
    void concurrentCallersShareBatches() throws Exception {
        UUID hot = account("0.00");
        List<UUID> payers = new ArrayList<>();
        for (int i = 0; i < 20; i++) payers.add(account("1000.00"));
        var before = batcher.stats();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<TransactionResponse>> results = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                UUID from = payers.get(i % payers.size());
                results.add(pool.submit(() -> {
                    gate.await();
                    return banking.transfer(new TransferRequest(from, hot, new BigDecimal("1.00"), key()));
                }));
            }
            gate.countDown();
            for (var f : results) assertNotNull(f.get(2, TimeUnit.MINUTES));
        } finally {
            pool.shutdownNow();
        }
        var after = batcher.stats();
        double average = (double) (after.transfers() - before.transfers()) / (after.batches() - before.batches());
        assertMoney("2000.00", balance(hot));
        assertTrue(average > 1.5, "Concurrent transfers should share batches; average batch size was " + average);
        System.out.printf("[batching] 2000 concurrent transfers into one account: average batch size %.1f%n", average);
    }
}
