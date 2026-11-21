package com.smartbank.transaction;

import com.smartbank.account.Account;
import com.smartbank.account.AccountNotFoundException;
import com.smartbank.account.AccountRepository;
import com.smartbank.common.UuidOrder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Group commit for transfers. Concurrent transfer requests queue up; a worker takes
 * everything queued (up to a maximum) and posts it in one database transaction:
 *
 * 1. Look up all request keys in one query; committed ones are replayed.
 * 2. Lock every account the batch touches with one
 *    {@code SELECT … ORDER BY id FOR NO KEY UPDATE}, in {@link UuidOrder}, the same
 *    order every other path uses.
 * 3. Decide each transfer in arrival order against the locked balances, as if they
 *    ran one after another. A rejected transfer (insufficient funds, unknown account,
 *    different currencies) gets its own error and does not affect the others.
 * 4. Write all accepted transfers, their ledger entries, events and webhook
 *    deliveries in one statement, and the net balance change per account in one
 *    batch.
 * 5. Commit, then answer every caller. A caller hears "success" only after commit.
 *
 * About five round trips per batch instead of about six per transfer, and a busy
 * account's row lock is taken once per batch instead of once per transfer.
 *
 * If the batch fails as a whole because another path inserted one of its request
 * keys at the same moment, or the batch lost a deadlock with such a path, each
 * transfer is retried on the unbatched path, which replays or posts it. Any other failure (for example a lost connection) is
 * returned to every caller; retrying with the same key is safe.
 *
 * Batches form naturally: while a worker posts one batch, new requests queue for
 * the next, so batches stay small under light load and grow under heavy load. An
 * optional linger waits a little longer for more requests.
 */
@Component
public class TransferBatcher {
    private static final Logger log = LoggerFactory.getLogger(TransferBatcher.class);

    record Pending(TransferRequest request, CompletableFuture<TransactionResponse> result) { }

    private final BankingService banking;
    private final AccountRepository accounts;
    private final BankingTransactionRepository transactions;
    private final PostingWriter writer;
    private final TransactionTemplate transactionTemplate;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int maxBatch;
    private final long lingerNanos;
    private final Duration callerTimeout;
    private volatile boolean running = true;

    private final AtomicLong batches = new AtomicLong(), transfers = new AtomicLong(), fallbacks = new AtomicLong();

    public TransferBatcher(BankingService banking, AccountRepository accounts,
                           BankingTransactionRepository transactions, PostingWriter writer,
                           TransactionTemplate transactionTemplate,
                           @Value("${smartbank.batching.workers:2}") int workerCount,
                           @Value("${smartbank.batching.max-size:200}") int maxBatch,
                           @Value("${smartbank.batching.linger:0ms}") Duration linger,
                           @Value("${smartbank.batching.caller-timeout:30s}") Duration callerTimeout) {
        this.banking = banking;
        this.accounts = accounts;
        this.transactions = transactions;
        this.writer = writer;
        this.transactionTemplate = transactionTemplate;
        this.maxBatch = maxBatch;
        this.lingerNanos = linger.toNanos();
        this.callerTimeout = callerTimeout;
        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofPlatform().name("transfer-batcher-" + i).daemon().start(this::work);
            workers.add(worker);
        }
    }

    /** Queues the transfer and waits for its batch to commit. */
    public TransactionResponse submit(TransferRequest request) {
        Pending pending = new Pending(request, new CompletableFuture<>());
        if (!running) throw new IllegalStateException("Transfer batcher is stopped");
        queue.add(pending);
        try {
            return pending.result().get(callerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            // The transfer may still commit; a retry with the same key will find out.
            throw new IllegalStateException("Timed out waiting for the transfer's batch to commit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the transfer's batch");
        }
    }

    public record Stats(long batches, long transfers, long fallbacks) {
        public double averageBatchSize() { return batches == 0 ? 0 : (double) transfers / batches; }
    }

    public Stats stats() {
        return new Stats(batches.get(), transfers.get(), fallbacks.get());
    }

    private void work() {
        while (running) {
            try {
                Pending first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<Pending> batch = new ArrayList<>(List.of(first));
                queue.drainTo(batch, maxBatch - 1);
                long deadline = System.nanoTime() + lingerNanos;
                while (batch.size() < maxBatch && System.nanoTime() < deadline) {
                    Pending next = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    batch.add(next);
                    queue.drainTo(batch, maxBatch - batch.size());
                }
                process(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Transfer batch worker error", e);
            }
        }
    }

    /** Posts one batch and answers every caller in it. Package-visible for tests. */
    void process(List<Pending> batch) {
        batches.incrementAndGet();
        transfers.addAndGet(batch.size());
        Map<Pending, Outcome> outcomes;
        try {
            outcomes = transactionTemplate.execute(status -> postBatch(batch));
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // Another path inserted one of these request keys concurrently, or the batch lost a deadlock
            // with such a path (it locks accounts before inserting keys; the direct path does the
            // reverse). Settle each transfer on its own: the contested one is replayed, the others post.
            fallbacks.incrementAndGet();
            for (Pending p : batch) {
                try {
                    p.result().complete(banking.transferDirect(p.request()));
                } catch (RuntimeException ex) {
                    p.result().completeExceptionally(ex);
                }
            }
            return;
        } catch (RuntimeException e) {
            batch.forEach(p -> p.result().completeExceptionally(e));
            return;
        }
        // Committed: now it is safe to tell callers.
        outcomes.forEach((p, o) -> {
            if (o.error() != null) p.result().completeExceptionally(o.error());
            else p.result().complete(o.response());
        });
    }

    record Outcome(TransactionResponse response, RuntimeException error) {
        static Outcome ok(TransactionResponse r) { return new Outcome(r, null); }
        static Outcome failed(RuntimeException e) { return new Outcome(null, e); }
    }

    private Map<Pending, Outcome> postBatch(List<Pending> batch) {
        Map<Pending, Outcome> outcomes = new LinkedHashMap<>();

        // 1. Request keys already committed are replays.
        Map<String, BankingTransaction> committed = new HashMap<>();
        transactions.findByRequestKeyIn(batch.stream().map(p -> p.request().requestKey()).distinct().toList())
                .forEach(tx -> committed.put(tx.getRequestKey(), tx));

        // 2. Lock every account the new transfers touch, in the shared lock order.
        Set<UUID> ids = new TreeSet<>(UuidOrder.COMPARATOR);
        for (Pending p : batch)
            if (!committed.containsKey(p.request().requestKey())) {
                ids.add(p.request().fromAccountId());
                ids.add(p.request().toAccountId());
            }
        Map<UUID, Account> locked = new HashMap<>();
        if (!ids.isEmpty()) accounts.lockAllInOrder(ids).forEach(a -> locked.put(a.getId(), a));
        Map<UUID, BigDecimal> available = new HashMap<>();
        locked.values().forEach(a -> available.put(a.getId(), a.getAvailableBalance()));

        // 3. Decide each transfer in arrival order.
        Map<UUID, BigDecimal> net = new TreeMap<>(UuidOrder.COMPARATOR);
        List<BankingTransaction> rows = new ArrayList<>();
        List<Pending> accepted = new ArrayList<>();
        Map<String, Pending> acceptedByKey = new HashMap<>();
        List<Pending> followers = new ArrayList<>();
        for (Pending p : batch) {
            TransferRequest r = p.request();
            BankingTransaction prior = committed.get(r.requestKey());
            if (prior != null) {
                outcomes.put(p, replay(prior, r));
                continue;
            }
            if (acceptedByKey.containsKey(r.requestKey())) {
                followers.add(p);  // same key as an earlier transfer in this batch
                continue;
            }
            Account source = locked.get(r.fromAccountId()), target = locked.get(r.toAccountId());
            if (source == null) { outcomes.put(p, Outcome.failed(new AccountNotFoundException(r.fromAccountId()))); continue; }
            if (target == null) { outcomes.put(p, Outcome.failed(new AccountNotFoundException(r.toAccountId()))); continue; }
            if (!source.getCurrency().equals(target.getCurrency())) {
                outcomes.put(p, Outcome.failed(new BankingException("Cross-currency transfers are not supported")));
                continue;
            }
            if (available.get(r.fromAccountId()).compareTo(r.amount()) < 0) {
                outcomes.put(p, Outcome.failed(new BankingException("Insufficient funds")));
                continue;
            }
            available.merge(r.fromAccountId(), r.amount().negate(), BigDecimal::add);
            available.merge(r.toAccountId(), r.amount(), BigDecimal::add);
            net.merge(r.fromAccountId(), r.amount().negate(), BigDecimal::add);
            net.merge(r.toAccountId(), r.amount(), BigDecimal::add);
            rows.add(new BankingTransaction(TransactionType.TRANSFER, r.fromAccountId(), r.toAccountId(),
                    r.amount(), source.getCurrency(), r.requestKey()));
            accepted.add(p);
            acceptedByKey.put(r.requestKey(), p);
        }

        // 4. One statement for all postings, one batch for all balance changes.
        if (!rows.isEmpty()) {
            List<TransactionResponse> posted = writer.postAll(rows);
            for (int i = 0; i < accepted.size(); i++) outcomes.put(accepted.get(i), Outcome.ok(posted.get(i)));
            List<PostingWriter.BalanceChange> changes = net.entrySet().stream()
                    .filter(e -> e.getValue().signum() != 0)
                    .map(e -> new PostingWriter.BalanceChange(e.getKey(), e.getValue()))
                    .toList();
            boolean[] applied = writer.applyBalanceChanges(changes);
            for (boolean ok : applied)
                if (!ok) throw new IllegalStateException("Balance check failed on locked accounts");
        }
        for (Pending f : followers) {
            Pending leader = acceptedByKey.get(f.request().requestKey());
            outcomes.put(f, sameTransfer(leader.request(), f.request())
                    ? outcomes.get(leader)
                    : Outcome.failed(new BankingException("Request key was already used for a different operation")));
        }
        return outcomes;
    }

    private Outcome replay(BankingTransaction prior, TransferRequest request) {
        try {
            return Outcome.ok(banking.replayTransfer(prior, request));
        } catch (BankingException e) {
            return Outcome.failed(e);
        }
    }

    private static boolean sameTransfer(TransferRequest a, TransferRequest b) {
        return a.fromAccountId().equals(b.fromAccountId()) && a.toAccountId().equals(b.toAccountId())
                && a.amount().compareTo(b.amount()) == 0;
    }

    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        List<Pending> left = new ArrayList<>();
        queue.drainTo(left);
        left.forEach(p -> p.result().completeExceptionally(new IllegalStateException("Transfer batcher stopped")));
    }
}
