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
 * Group commit for transfers, deposits and withdrawals. Concurrent requests queue
 * up; a worker takes everything queued (up to a maximum) and posts it in one
 * database transaction:
 *
 * 1. Look up all request keys in one query; committed ones are replayed.
 * 2. Lock every account the batch touches with one
 *    {@code SELECT … ORDER BY id FOR NO KEY UPDATE}, in {@link UuidOrder}, the same
 *    order every other path uses.
 * 3. Decide each posting in arrival order against the locked balances, as if they
 *    ran one after another: a deposit needs its account to exist, a withdrawal needs
 *    available funds, a transfer needs both accounts, one currency and funds. A
 *    rejected posting gets its own error and does not affect the others, and a
 *    withdrawal can use money deposited earlier in the same batch.
 * 4. Write all accepted postings, their ledger entries, events and webhook
 *    deliveries in one statement, and the net balance change per account in one
 *    batch.
 * 5. Commit, then answer every caller. A caller hears "success" only after commit.
 *
 * About five round trips per batch instead of about six per posting, and a busy
 * account's row lock is taken once per batch instead of once per posting.
 *
 * If the batch fails as a whole because another path inserted one of its request
 * keys at the same moment, or the batch lost a deadlock with such a path, each
 * posting is retried on the unbatched path, which replays or posts it. Any other failure (for example a lost connection) is
 * returned to every caller; retrying with the same key is safe.
 *
 * Batches form naturally: while a worker posts one batch, new requests queue for
 * the next, so batches stay small under light load and grow under heavy load. An
 * optional linger waits a little longer for more requests.
 */
@Component
public class PostingBatcher {
    private static final Logger log = LoggerFactory.getLogger(PostingBatcher.class);

    /**
     * One deposit (from null), withdrawal (to null) or transfer (both set), with its
     * request key.
     */
    record Posting(TransactionType type, UUID from, UUID to, BigDecimal amount, String key) {
        static Posting transfer(TransferRequest r) {
            return new Posting(TransactionType.TRANSFER, r.fromAccountId(), r.toAccountId(), r.amount(), r.requestKey());
        }
        static Posting deposit(UUID account, MoneyRequest r) {
            return new Posting(TransactionType.DEPOSIT, null, account, r.amount(), r.requestKey());
        }
        static Posting withdrawal(UUID account, MoneyRequest r) {
            return new Posting(TransactionType.WITHDRAWAL, account, null, r.amount(), r.requestKey());
        }
        /** The account a single-account posting is about. */
        UUID account() { return type == TransactionType.DEPOSIT ? to : from; }
        boolean sameAs(Posting o) {
            return type == o.type && java.util.Objects.equals(from, o.from) && java.util.Objects.equals(to, o.to)
                    && amount.compareTo(o.amount) == 0;
        }
    }

    record Pending(Posting posting, CompletableFuture<TransactionResponse> result) { }

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

    public PostingBatcher(BankingService banking, AccountRepository accounts,
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
            Thread worker = Thread.ofPlatform().name("posting-batcher-" + i).daemon().start(this::work);
            workers.add(worker);
        }
    }

    /** Queues the posting and waits for its batch to commit. */
    public TransactionResponse submit(Posting posting) {
        Pending pending = new Pending(posting, new CompletableFuture<>());
        if (!running) throw new IllegalStateException("Posting batcher is stopped");
        queue.add(pending);
        try {
            return pending.result().get(callerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            // The transfer may still commit; a retry with the same key will find out.
            throw new IllegalStateException("Timed out waiting for the batch to commit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the batch");
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
                log.error("Posting batch worker error", e);
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
            // reverse). Settle each posting on its own: the contested one is replayed, the others post.
            fallbacks.incrementAndGet();
            for (Pending p : batch) {
                try {
                    p.result().complete(direct(p.posting()));
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

    private TransactionResponse direct(Posting p) {
        return switch (p.type()) {
            case TRANSFER -> banking.transferDirect(new TransferRequest(p.from(), p.to(), p.amount(), p.key()));
            case DEPOSIT -> banking.depositDirect(p.to(), new MoneyRequest(p.amount(), p.key()));
            case WITHDRAWAL -> banking.withdrawDirect(p.from(), new MoneyRequest(p.amount(), p.key()));
            default -> throw new IllegalArgumentException("Not batchable: " + p.type());
        };
    }

    private Map<Pending, Outcome> postBatch(List<Pending> batch) {
        Map<Pending, Outcome> outcomes = new LinkedHashMap<>();

        // 1. Request keys already committed are replays.
        Map<String, BankingTransaction> committed = new HashMap<>();
        transactions.findByRequestKeyIn(batch.stream().map(p -> p.posting().key()).distinct().toList())
                .forEach(tx -> committed.put(tx.getRequestKey(), tx));

        // 2. Lock every account the new postings touch, in the shared lock order.
        Set<UUID> ids = new TreeSet<>(UuidOrder.COMPARATOR);
        for (Pending p : batch)
            if (!committed.containsKey(p.posting().key())) {
                if (p.posting().from() != null) ids.add(p.posting().from());
                if (p.posting().to() != null) ids.add(p.posting().to());
            }
        Map<UUID, Account> locked = new HashMap<>();
        if (!ids.isEmpty()) accounts.lockAllInOrder(ids).forEach(a -> locked.put(a.getId(), a));
        Map<UUID, BigDecimal> available = new HashMap<>();
        locked.values().forEach(a -> available.put(a.getId(), a.getAvailableBalance()));

        // 3. Decide each posting in arrival order.
        Map<UUID, BigDecimal> net = new TreeMap<>(UuidOrder.COMPARATOR);
        List<BankingTransaction> rows = new ArrayList<>();
        List<Pending> accepted = new ArrayList<>();
        Map<String, Pending> acceptedByKey = new HashMap<>();
        List<Pending> followers = new ArrayList<>();
        for (Pending p : batch) {
            Posting r = p.posting();
            BankingTransaction prior = committed.get(r.key());
            if (prior != null) {
                outcomes.put(p, replay(prior, r));
                continue;
            }
            if (acceptedByKey.containsKey(r.key())) {
                followers.add(p);  // same key as an earlier posting in this batch
                continue;
            }
            RuntimeException rejection = check(r, locked, available);
            if (rejection != null) {
                outcomes.put(p, Outcome.failed(rejection));
                continue;
            }
            if (r.from() != null) {
                available.merge(r.from(), r.amount().negate(), BigDecimal::add);
                net.merge(r.from(), r.amount().negate(), BigDecimal::add);
            }
            if (r.to() != null) {
                available.merge(r.to(), r.amount(), BigDecimal::add);
                net.merge(r.to(), r.amount(), BigDecimal::add);
            }
            String currency = locked.get(r.from() != null ? r.from() : r.to()).getCurrency();
            rows.add(new BankingTransaction(r.type(), r.from(), r.to(), r.amount(), currency, r.key()));
            accepted.add(p);
            acceptedByKey.put(r.key(), p);
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
            Pending leader = acceptedByKey.get(f.posting().key());
            outcomes.put(f, leader.posting().sameAs(f.posting())
                    ? outcomes.get(leader)
                    : Outcome.failed(new BankingException("Request key was already used for a different operation")));
        }
        return outcomes;
    }

    /** Why the posting cannot be accepted given the balances so far, or null if it can. */
    private static RuntimeException check(Posting r, Map<UUID, Account> locked, Map<UUID, BigDecimal> available) {
        Account source = r.from() == null ? null : locked.get(r.from());
        Account target = r.to() == null ? null : locked.get(r.to());
        if (r.from() != null && source == null) return new AccountNotFoundException(r.from());
        if (r.to() != null && target == null) return new AccountNotFoundException(r.to());
        if (source != null && target != null && !source.getCurrency().equals(target.getCurrency()))
            return new BankingException("Cross-currency transfers are not supported");
        if (source != null && available.get(r.from()).compareTo(r.amount()) < 0)
            return new BankingException("Insufficient funds");
        return null;
    }

    private Outcome replay(BankingTransaction prior, Posting r) {
        try {
            return Outcome.ok(r.type() == TransactionType.TRANSFER
                    ? banking.replayTransfer(prior, new TransferRequest(r.from(), r.to(), r.amount(), r.key()))
                    : banking.replaySingle(prior, r.type(), r.account(), r.amount()));
        } catch (BankingException e) {
            return Outcome.failed(e);
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        List<Pending> left = new ArrayList<>();
        queue.drainTo(left);
        left.forEach(p -> p.result().completeExceptionally(new IllegalStateException("Posting batcher stopped")));
    }
}
