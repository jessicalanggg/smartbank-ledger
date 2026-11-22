package com.smartbank.transaction;

import com.smartbank.account.Account;
import com.smartbank.account.AccountNotFoundException;
import com.smartbank.account.AccountRepository;
import com.smartbank.common.Initiator;
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
 * Group commit for transfers, deposits, withdrawals and linked transfers. Concurrent requests queue
 * up; a worker takes everything queued (up to a maximum) and posts it in one
 * database transaction:
 *
 * 1. Look up all request keys in one query; committed ones are replayed.
 * 2. Lock every account the batch touches with one
 *    {@code SELECT … ORDER BY id FOR NO KEY UPDATE}, in {@link UuidOrder}, the same
 *    order every other path uses.
 * 3. Decide each posting in arrival order against the locked balances, as if they
 *    ran one after another: a deposit needs its account to exist, a withdrawal needs
 *    available funds, a transfer needs both accounts, one currency and funds, and a
 *    linked transfer needs all of that for every leg, with funds checked on each
 *    account's net change across the group. A rejected posting (or group) gets its
 *    own error and does not affect the others, and a withdrawal can use money
 *    deposited earlier in the same batch.
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
     * One deposit (from null), withdrawal (to null), transfer (both set), or linked
     * transfer (legs set, all posting together or not at all), with its request key.
     */
    record Posting(TransactionType type, UUID from, UUID to, BigDecimal amount, String key,
                   List<LinkedTransferRequest.Leg> legs, UUID initiatedBy) {
        // The initiator is read here, on the caller's thread: batch workers have no security context.
        static Posting transfer(TransferRequest r) {
            return new Posting(TransactionType.TRANSFER, r.fromAccountId(), r.toAccountId(), r.amount(), r.requestKey(), null,
                    Initiator.current());
        }
        static Posting deposit(UUID account, MoneyRequest r) {
            return new Posting(TransactionType.DEPOSIT, null, account, r.amount(), r.requestKey(), null, Initiator.current());
        }
        static Posting withdrawal(UUID account, MoneyRequest r) {
            return new Posting(TransactionType.WITHDRAWAL, account, null, r.amount(), r.requestKey(), null, Initiator.current());
        }
        static Posting linked(LinkedTransferRequest r) {
            return new Posting(TransactionType.TRANSFER, null, null, null, r.requestKey(), List.copyOf(r.transfers()),
                    Initiator.current());
        }
        boolean isLinked() { return legs != null; }
        /** The account a single-account posting is about. */
        UUID account() { return type == TransactionType.DEPOSIT ? to : from; }
        boolean sameAs(Posting o) {
            if (isLinked() || o.isLinked()) return isLinked() && o.isLinked() && sameLegs(legs, o.legs);
            return type == o.type && java.util.Objects.equals(from, o.from) && java.util.Objects.equals(to, o.to)
                    && amount.compareTo(o.amount) == 0;
        }
        private static boolean sameLegs(List<LinkedTransferRequest.Leg> a, List<LinkedTransferRequest.Leg> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++)
                if (!a.get(i).fromAccountId().equals(b.get(i).fromAccountId()) || !a.get(i).toAccountId().equals(b.get(i).toAccountId())
                        || a.get(i).amount().compareTo(b.get(i).amount()) != 0) return false;
            return true;
        }
        /** Every account the posting touches. */
        List<UUID> accounts() {
            List<UUID> ids = new ArrayList<>();
            if (isLinked()) legs.forEach(l -> { ids.add(l.fromAccountId()); ids.add(l.toAccountId()); });
            else { if (from != null) ids.add(from); if (to != null) ids.add(to); }
            return ids;
        }
    }

    /** The result is a TransactionResponse, or a LinkedTransferResponse for a linked transfer. */
    record Pending(Posting posting, CompletableFuture<Object> result) { }

    private final BankingService banking;
    private final AccountRepository accounts;
    private final BankingTransactionRepository transactions;
    private final PostingWriter writer;
    private final TransactionTemplate transactionTemplate;
    private final com.smartbank.common.Currencies currencies;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final int maxBatch;
    private final long lingerNanos;
    private final Duration callerTimeout;
    private volatile boolean running = true;

    private final AtomicLong batches = new AtomicLong(), transfers = new AtomicLong(), fallbacks = new AtomicLong();

    public PostingBatcher(BankingService banking, AccountRepository accounts,
                           BankingTransactionRepository transactions, PostingWriter writer,
                           TransactionTemplate transactionTemplate, com.smartbank.common.Currencies currencies,
                           @Value("${smartbank.batching.workers:2}") int workerCount,
                           @Value("${smartbank.batching.max-size:200}") int maxBatch,
                           @Value("${smartbank.batching.linger:0ms}") Duration linger,
                           @Value("${smartbank.batching.caller-timeout:30s}") Duration callerTimeout) {
        this.banking = banking;
        this.accounts = accounts;
        this.transactions = transactions;
        this.writer = writer;
        this.transactionTemplate = transactionTemplate;
        this.currencies = currencies;
        this.maxBatch = maxBatch;
        this.lingerNanos = linger.toNanos();
        this.callerTimeout = callerTimeout;
        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofPlatform().name("posting-batcher-" + i).daemon().start(this::work);
            workers.add(worker);
        }
    }

    /** Queues a deposit, withdrawal or transfer and waits for its batch to commit. */
    public TransactionResponse submit(Posting posting) {
        return (TransactionResponse) await(posting);
    }

    /** Queues a linked transfer and waits for its batch to commit. */
    public LinkedTransferResponse submitLinked(LinkedTransferRequest request) {
        return (LinkedTransferResponse) await(Posting.linked(request));
    }

    private Object await(Posting posting) {
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

    record Outcome(Object response, RuntimeException error) {
        static Outcome ok(Object r) { return new Outcome(r, null); }
        static Outcome failed(RuntimeException e) { return new Outcome(null, e); }
    }

    private Object direct(Posting p) {
        if (p.isLinked()) return banking.linkedTransferDirect(new LinkedTransferRequest(p.legs(), p.key()));
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
            if (!committed.containsKey(p.posting().key())) ids.addAll(p.posting().accounts());
        Map<UUID, Account> locked = new HashMap<>();
        if (!ids.isEmpty()) accounts.lockAllInOrder(ids).forEach(a -> locked.put(a.getId(), a));
        Map<UUID, BigDecimal> available = new HashMap<>();
        locked.values().forEach(a -> available.put(a.getId(), a.getAvailableBalance()));

        // 3. Decide each posting in arrival order.
        Map<UUID, BigDecimal> net = new TreeMap<>(UuidOrder.COMPARATOR);
        List<BankingTransaction> rows = new ArrayList<>();
        List<Pending> accepted = new ArrayList<>();
        Map<Pending, UUID> linkIds = new HashMap<>();
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
            if (r.isLinked()) {
                // All legs or none: check the group as a whole against the balances so far.
                RuntimeException rejection = checkLinked(r, locked, available);
                if (rejection != null) {
                    outcomes.put(p, Outcome.failed(rejection));
                    continue;
                }
                UUID linkId = UUID.randomUUID();
                for (int i = 0; i < r.legs().size(); i++) {
                    var leg = r.legs().get(i);
                    available.merge(leg.fromAccountId(), leg.amount().negate(), BigDecimal::add);
                    available.merge(leg.toAccountId(), leg.amount(), BigDecimal::add);
                    net.merge(leg.fromAccountId(), leg.amount().negate(), BigDecimal::add);
                    net.merge(leg.toAccountId(), leg.amount(), BigDecimal::add);
                    rows.add(BankingTransaction.linkedLeg(linkId, i, leg.fromAccountId(), leg.toAccountId(), leg.amount(),
                            locked.get(leg.fromAccountId()).getCurrency(), r.key()).initiatedBy(r.initiatedBy()));
                }
                linkIds.put(p, linkId);
                accepted.add(p);
                acceptedByKey.put(r.key(), p);
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
            rows.add(new BankingTransaction(r.type(), r.from(), r.to(), r.amount(), currency, r.key())
                    .initiatedBy(r.initiatedBy()));
            accepted.add(p);
            acceptedByKey.put(r.key(), p);
        }

        // 4. One statement for all postings, one batch for all balance changes.
        if (!rows.isEmpty()) {
            List<TransactionResponse> posted = writer.postAll(rows);
            int row = 0;
            for (Pending p : accepted) {
                if (p.posting().isLinked()) {
                    int legs = p.posting().legs().size();
                    outcomes.put(p, Outcome.ok(new LinkedTransferResponse(linkIds.get(p), List.copyOf(posted.subList(row, row + legs)))));
                    row += legs;
                } else {
                    outcomes.put(p, Outcome.ok(posted.get(row++)));
                }
            }
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
    private RuntimeException check(Posting r, Map<UUID, Account> locked, Map<UUID, BigDecimal> available) {
        Account source = r.from() == null ? null : locked.get(r.from());
        Account target = r.to() == null ? null : locked.get(r.to());
        if (r.from() != null && source == null) return new AccountNotFoundException(r.from());
        if (r.to() != null && target == null) return new AccountNotFoundException(r.to());
        if (source != null && target != null && !source.getCurrency().equals(target.getCurrency()))
            return new BankingException("Cross-currency transfers are not supported");
        try {
            currencies.requireValidAmount(r.amount(), (source != null ? source : target).getCurrency());
        } catch (BankingException e) {
            return e;
        }
        if (source != null && available.get(r.from()).compareTo(r.amount()) < 0)
            return new BankingException("Insufficient funds");
        return null;
    }

    /**
     * Why a linked transfer cannot post given the balances so far, or null if it can:
     * every leg's accounts must exist and share a currency, and every account must
     * cover its net change across the group. Same checks, in the same order, as the
     * unbatched path, so the error is the same.
     */
    private RuntimeException checkLinked(Posting r, Map<UUID, Account> locked, Map<UUID, BigDecimal> available) {
        Map<UUID, BigDecimal> groupNet = new TreeMap<>(UuidOrder.COMPARATOR);
        for (int i = 0; i < r.legs().size(); i++) {
            var leg = r.legs().get(i);
            Account source = locked.get(leg.fromAccountId()), target = locked.get(leg.toAccountId());
            if (source == null) return new AccountNotFoundException(leg.fromAccountId());
            if (target == null) return new AccountNotFoundException(leg.toAccountId());
            if (!source.getCurrency().equals(target.getCurrency()))
                return new BankingException("Transfer " + i + ": cross-currency transfers are not supported");
            try {
                currencies.requireValidAmount(leg.amount(), source.getCurrency());
            } catch (BankingException e) {
                return e;
            }
            groupNet.merge(leg.fromAccountId(), leg.amount().negate(), BigDecimal::add);
            groupNet.merge(leg.toAccountId(), leg.amount(), BigDecimal::add);
        }
        for (var change : groupNet.entrySet())
            if (change.getValue().signum() < 0 && available.get(change.getKey()).compareTo(change.getValue().negate()) < 0)
                return new BankingException("Insufficient funds in account " + change.getKey());
        return null;
    }

    private Outcome replay(BankingTransaction prior, Posting r) {
        if (r.isLinked()) {
            try {
                return Outcome.ok(banking.replayLinked(prior, r.legs()));
            } catch (BankingException e) {
                return Outcome.failed(e);
            }
        }
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
