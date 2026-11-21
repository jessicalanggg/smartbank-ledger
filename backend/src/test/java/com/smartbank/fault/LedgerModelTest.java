package com.smartbank.fault;

import com.smartbank.account.AccountNotFoundException;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Model-based randomized testing. Random sequences of operations (deposits,
 * withdrawals, transfers, linked transfers, reversals, exact retries, conflicting key reuse,
 * holds and their post/void/expiry) run against PostgreSQL. A small in-memory model, written
 * independently of the service code, predicts each outcome and every balance.
 * After each operation the outcome and all balances must match the model; at the
 * end of each sequence the ledger must reconcile.
 *
 * On a mismatch the sequence is shrunk to the smallest one that still fails and
 * reported with its seed. Reproduce with -Dmodel.seed=<seed>. More coverage:
 * -Dmodel.sequences=500 -Dmodel.length=200.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
class LedgerModelTest {
    private static final int ACCOUNTS = 4;
    private static final BigDecimal OPENING = new BigDecimal("100.00");
    private static final Instant FAR_FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired BankingService banking;
    @Autowired HoldService holds;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;

    // ---- Operations ----------------------------------------------------------

    enum Outcome { OK, REJECTED, NOT_FOUND }

    sealed interface Op permits Deposit, Withdraw, Transfer, Linked, Reverse, CreateHold, PostHold, VoidHold, ExpireHold { }
    record Deposit(int account, BigDecimal amount, String key) implements Op { }
    record Withdraw(int account, BigDecimal amount, String key) implements Op { }
    record Transfer(int from, int to, BigDecimal amount, String key) implements Op { }
    record Leg(int from, int to, BigDecimal amount) { }
    /** A linked transfer: every leg posts or none does. */
    record Linked(List<Leg> legs, String key) implements Op { }
    /** Reverses the deposit, withdrawal or transfer made with originalKey; amount null reverses what is left. */
    record Reverse(String originalKey, BigDecimal amount, String key) implements Op { }
    record CreateHold(int from, int to, BigDecimal amount, String key) implements Op { }
    /** amount null posts the full hold. Holds are referred to by their creation key. */
    record PostHold(String holdKey, BigDecimal amount) implements Op { }
    record VoidHold(String holdKey) implements Op { }
    record ExpireHold(String holdKey) implements Op { }

    // ---- Reference model (independent of the service implementation) -------

    static final class Model {
        final BigDecimal[] balance = new BigDecimal[ACCOUNTS];
        final BigDecimal[] pendingDebits = new BigDecimal[ACCOUNTS];
        final BigDecimal[] pendingCredits = new BigDecimal[ACCOUNTS];
        /** Request key to the money operation that first used it successfully. */
        final Map<String, Op> moneyKeys = new HashMap<>();
        /** Amount reversed so far, by the original's request key. */
        final Map<String, BigDecimal> reversed = new HashMap<>();
        final Map<String, ModelHold> holdsByKey = new HashMap<>();

        static final class ModelHold {
            final CreateHold create;
            String status = "PENDING";
            BigDecimal posted;
            ModelHold(CreateHold create) { this.create = create; }
        }

        Model() {
            Arrays.fill(balance, OPENING);
            Arrays.fill(pendingDebits, BigDecimal.ZERO);
            Arrays.fill(pendingCredits, BigDecimal.ZERO);
        }

        BigDecimal available(int a) { return balance[a].subtract(pendingDebits[a]); }

        Outcome apply(Op op) {
            switch (op) {
                case Deposit d -> {
                    Op prior = moneyKeys.get(d.key());
                    if (prior != null) return sameRequest(prior, d) ? Outcome.OK : Outcome.REJECTED;
                    balance[d.account()] = balance[d.account()].add(d.amount());
                    moneyKeys.put(d.key(), d);
                    return Outcome.OK;
                }
                case Withdraw w -> {
                    Op prior = moneyKeys.get(w.key());
                    if (prior != null) return sameRequest(prior, w) ? Outcome.OK : Outcome.REJECTED;
                    if (available(w.account()).compareTo(w.amount()) < 0) return Outcome.REJECTED;
                    balance[w.account()] = balance[w.account()].subtract(w.amount());
                    moneyKeys.put(w.key(), w);
                    return Outcome.OK;
                }
                case Transfer t -> {
                    if (t.from() == t.to()) return Outcome.REJECTED;
                    Op prior = moneyKeys.get(t.key());
                    if (prior != null) return sameRequest(prior, t) ? Outcome.OK : Outcome.REJECTED;
                    if (available(t.from()).compareTo(t.amount()) < 0) return Outcome.REJECTED;
                    balance[t.from()] = balance[t.from()].subtract(t.amount());
                    balance[t.to()] = balance[t.to()].add(t.amount());
                    moneyKeys.put(t.key(), t);
                    return Outcome.OK;
                }
                case Linked l -> {
                    if (l.legs().stream().anyMatch(leg -> leg.from() == leg.to())) return Outcome.REJECTED;
                    Op prior = moneyKeys.get(l.key());
                    if (prior != null) return sameRequest(prior, l) ? Outcome.OK : Outcome.REJECTED;
                    // Funds are checked on each account's net change across the group.
                    BigDecimal[] net = new BigDecimal[ACCOUNTS];
                    Arrays.fill(net, BigDecimal.ZERO);
                    for (Leg leg : l.legs()) {
                        net[leg.from()] = net[leg.from()].subtract(leg.amount());
                        net[leg.to()] = net[leg.to()].add(leg.amount());
                    }
                    for (int a = 0; a < ACCOUNTS; a++)
                        if (net[a].signum() < 0 && available(a).compareTo(net[a].negate()) < 0) return Outcome.REJECTED;
                    for (int a = 0; a < ACCOUNTS; a++) balance[a] = balance[a].add(net[a]);
                    moneyKeys.put(l.key(), l);
                    return Outcome.OK;
                }
                case Reverse v -> {
                    Op prior = moneyKeys.get(v.key());
                    if (prior != null)
                        return prior instanceof Reverse p && p.originalKey().equals(v.originalKey())
                                && (v.amount() == null || reversalAmount(p).compareTo(v.amount()) == 0)
                                ? Outcome.OK : Outcome.REJECTED;
                    Op original = moneyKeys.get(v.originalKey());
                    // Reversals target single transactions; the test does not address a linked group by id.
                    if (original == null || original instanceof Linked) return Outcome.NOT_FOUND;
                    if (!(original instanceof Deposit || original instanceof Withdraw || original instanceof Transfer))
                        return Outcome.REJECTED;
                    BigDecimal originalAmount = switch (original) {
                        case Deposit d -> d.amount();
                        case Withdraw w -> w.amount();
                        case Transfer t -> t.amount();
                        default -> throw new IllegalStateException();
                    };
                    BigDecimal remaining = originalAmount.subtract(reversed.getOrDefault(v.originalKey(), BigDecimal.ZERO));
                    if (remaining.signum() <= 0) return Outcome.REJECTED;
                    BigDecimal amount = v.amount() == null ? remaining : v.amount();
                    if (amount.compareTo(remaining) > 0) return Outcome.REJECTED;
                    // The money goes back the way it came.
                    Integer from = switch (original) {
                        case Deposit d -> d.account();
                        case Transfer t -> t.to();
                        default -> null;
                    };
                    Integer to = switch (original) {
                        case Withdraw w -> w.account();
                        case Transfer t -> t.from();
                        default -> null;
                    };
                    if (from != null && available(from).compareTo(amount) < 0) return Outcome.REJECTED;
                    if (from != null) balance[from] = balance[from].subtract(amount);
                    if (to != null) balance[to] = balance[to].add(amount);
                    reversed.merge(v.originalKey(), amount, BigDecimal::add);
                    resolvedReversals.put(v.key(), amount);
                    moneyKeys.put(v.key(), v);
                    return Outcome.OK;
                }
                case CreateHold c -> {
                    if (c.from() == c.to()) return Outcome.REJECTED;
                    ModelHold prior = holdsByKey.get(c.key());
                    if (prior != null) return sameRequest(prior.create, c) ? Outcome.OK : Outcome.REJECTED;
                    if (available(c.from()).compareTo(c.amount()) < 0) return Outcome.REJECTED;
                    pendingDebits[c.from()] = pendingDebits[c.from()].add(c.amount());
                    pendingCredits[c.to()] = pendingCredits[c.to()].add(c.amount());
                    holdsByKey.put(c.key(), new ModelHold(c));
                    return Outcome.OK;
                }
                case PostHold p -> {
                    ModelHold h = holdsByKey.get(p.holdKey());
                    if (h == null) return Outcome.NOT_FOUND;
                    BigDecimal amount = p.amount() == null ? h.create.amount() : p.amount();
                    switch (h.status) {
                        case "POSTED" -> { return h.posted.compareTo(amount) == 0 ? Outcome.OK : Outcome.REJECTED; }
                        case "VOIDED", "EXPIRED" -> { return Outcome.REJECTED; }
                        default -> { }
                    }
                    if (amount.compareTo(h.create.amount()) > 0) return Outcome.REJECTED;
                    release(h);
                    balance[h.create.from()] = balance[h.create.from()].subtract(amount);
                    balance[h.create.to()] = balance[h.create.to()].add(amount);
                    h.status = "POSTED";
                    h.posted = amount;
                    return Outcome.OK;
                }
                case VoidHold v -> {
                    ModelHold h = holdsByKey.get(v.holdKey());
                    if (h == null) return Outcome.NOT_FOUND;
                    if (h.status.equals("POSTED")) return Outcome.REJECTED;
                    if (h.status.equals("PENDING")) { release(h); h.status = "VOIDED"; }
                    return Outcome.OK;
                }
                case ExpireHold e -> {
                    ModelHold h = holdsByKey.get(e.holdKey());
                    if (h == null) return Outcome.NOT_FOUND;
                    if (h.status.equals("PENDING")) { release(h); h.status = "EXPIRED"; }
                    return Outcome.OK;
                }
            }
        }

        /** What each successful reversal actually reversed (its amount may have been "whatever is left"). */
        final Map<String, BigDecimal> resolvedReversals = new HashMap<>();

        private BigDecimal reversalAmount(Reverse r) {
            return resolvedReversals.get(r.key());
        }

        private void release(ModelHold h) {
            pendingDebits[h.create.from()] = pendingDebits[h.create.from()].subtract(h.create.amount());
            pendingCredits[h.create.to()] = pendingCredits[h.create.to()].subtract(h.create.amount());
        }

        private static boolean sameLegs(List<Leg> a, List<Leg> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++)
                if (a.get(i).from() != b.get(i).from() || a.get(i).to() != b.get(i).to()
                        || a.get(i).amount().compareTo(b.get(i).amount()) != 0) return false;
            return true;
        }

        /** Same operation type and parameters: amounts compared by value. */
        private static boolean sameRequest(Op a, Op b) {
            return switch (a) {
                case Deposit x when b instanceof Deposit y -> x.account() == y.account() && x.amount().compareTo(y.amount()) == 0;
                case Withdraw x when b instanceof Withdraw y -> x.account() == y.account() && x.amount().compareTo(y.amount()) == 0;
                case Transfer x when b instanceof Transfer y -> x.from() == y.from() && x.to() == y.to() && x.amount().compareTo(y.amount()) == 0;
                case CreateHold x when b instanceof CreateHold y -> x.from() == y.from() && x.to() == y.to() && x.amount().compareTo(y.amount()) == 0;
                case Linked x when b instanceof Linked y -> sameLegs(x.legs(), y.legs());
                default -> false;
            };
        }
    }

    // ---- Generation ----------------------------------------------------------

    static List<Op> generate(long seed, int length) {
        Random r = new Random(seed);
        List<Op> ops = new ArrayList<>();
        List<Op> moneyOps = new ArrayList<>();
        List<CreateHold> holdOps = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int kind = r.nextInt(100);
            int a = r.nextInt(ACCOUNTS), b = r.nextInt(ACCOUNTS);
            BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 8_001), 2);  // 0.01 to 80.00
            String key = "s" + seed + "-k" + i;
            Op op;
            if (kind < 10 && !moneyOps.isEmpty()) {
                op = moneyOps.get(r.nextInt(moneyOps.size()));  // exact retry
            } else if (kind < 15 && !moneyOps.isEmpty()) {
                String reused = keyOf(moneyOps.get(r.nextInt(moneyOps.size())));  // conflicting reuse
                op = new Transfer(a, b, amount, reused);
            } else if (kind < 20 && !moneyOps.isEmpty()) {
                // Reverse an earlier operation (sometimes one that failed or is itself a reversal).
                String target = keyOf(moneyOps.get(r.nextInt(moneyOps.size())));
                if (r.nextInt(15) == 0) target = "missing-" + key;
                op = new Reverse(target, r.nextBoolean() ? null : BigDecimal.valueOf(r.nextInt(1, 4_001), 2), key);
            } else if (kind < 25) {
                op = new Deposit(a, amount, key);
            } else if (kind < 37) {
                op = new Withdraw(a, amount, key);
            } else if (kind < 50) {
                op = new Transfer(a, b, amount, key);  // a == b sometimes, which must be rejected
            } else if (kind < 57) {
                List<Leg> legs = new ArrayList<>();
                for (int n = r.nextInt(2, 5); n > 0; n--) {
                    int from = r.nextInt(ACCOUNTS), to = r.nextInt(ACCOUNTS);
                    if (from == to && r.nextInt(4) != 0) to = (to + 1) % ACCOUNTS;  // mostly valid legs
                    legs.add(new Leg(from, to, BigDecimal.valueOf(r.nextInt(1, 8_001), 2)));
                }
                op = new Linked(legs, key);
            } else if (kind < 70) {
                CreateHold c = r.nextInt(8) == 0 && !holdOps.isEmpty()
                        ? holdOps.get(r.nextInt(holdOps.size()))  // retry of a hold
                        : new CreateHold(a, b, amount, "h" + key);
                holdOps.add(c);
                op = c;
            } else if (holdOps.isEmpty()) {
                op = new Deposit(a, amount, key);
            } else {
                String holdKey = r.nextInt(20) == 0 ? "missing-" + key : holdOps.get(r.nextInt(holdOps.size())).key();
                op = switch (kind % 3) {
                    case 0 -> new PostHold(holdKey, r.nextBoolean() ? null : amount);
                    case 1 -> new VoidHold(holdKey);
                    default -> new ExpireHold(holdKey);
                };
            }
            if (op instanceof Deposit || op instanceof Withdraw || op instanceof Transfer || op instanceof Linked
                    || op instanceof Reverse) moneyOps.add(op);
            ops.add(op);
        }
        return ops;
    }

    private static String keyOf(Op op) {
        return switch (op) {
            case Deposit d -> d.key();
            case Withdraw w -> w.key();
            case Transfer t -> t.key();
            case Linked l -> l.key();
            case Reverse v -> v.key();
            default -> throw new IllegalArgumentException(op.toString());
        };
    }

    // ---- Execution -----------------------------------------------------------

    /** Why a sequence failed, or null if it passed. */
    record Failure(int step, String message) { }

    private List<UUID> resetAccounts() {
        LedgerTestData.truncateLedger(jdbc);
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'MODEL-%'");
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++)
            ids.add(LedgerTestData.openAccount(jdbc, "MODEL-" + i, OPENING, "CAD"));
        return ids;
    }

    private Failure run(List<Op> ops) {
        List<UUID> ids = resetAccounts();
        Map<String, UUID> holdIds = new HashMap<>();
        Map<String, UUID> txIds = new HashMap<>();
        Model model = new Model();
        for (int step = 0; step < ops.size(); step++) {
            Op op = ops.get(step);
            Outcome expected = model.apply(op);
            Outcome actual;
            try {
                execute(op, ids, holdIds, txIds);
                actual = Outcome.OK;
            } catch (HoldNotFoundException | AccountNotFoundException | TransactionNotFoundException e) {
                actual = Outcome.NOT_FOUND;
            } catch (BankingException e) {
                actual = Outcome.REJECTED;
            } catch (RuntimeException e) {
                return new Failure(step, op + " threw " + e);
            }
            if (actual != expected)
                return new Failure(step, op + ": expected " + expected + " but was " + actual);
            String stateMismatch = compareState(model, ids);
            if (stateMismatch != null)
                return new Failure(step, "after " + op + ": " + stateMismatch);
        }
        var report = reconciliation.run();
        if (!report.isClean()) return new Failure(ops.size(), "books out of balance: " + report);
        return null;
    }

    private void execute(Op op, List<UUID> ids, Map<String, UUID> holdIds, Map<String, UUID> txIds) {
        switch (op) {
            case Deposit d -> txIds.putIfAbsent(d.key(),
                    banking.deposit(ids.get(d.account()), new MoneyRequest(d.amount(), d.key())).id());
            case Withdraw w -> txIds.putIfAbsent(w.key(),
                    banking.withdraw(ids.get(w.account()), new MoneyRequest(w.amount(), w.key())).id());
            case Transfer t -> txIds.putIfAbsent(t.key(), banking.transfer(
                    new TransferRequest(ids.get(t.from()), ids.get(t.to()), t.amount(), t.key())).id());
            case Reverse v -> txIds.putIfAbsent(v.key(), banking.reverse(
                    txIds.getOrDefault(v.originalKey(), UUID.nameUUIDFromBytes(v.originalKey().getBytes())),
                    new ReversalRequest(v.amount(), v.key())).id());
            case Linked l -> banking.linkedTransfer(new LinkedTransferRequest(l.legs().stream()
                    .map(leg -> new LinkedTransferRequest.Leg(ids.get(leg.from()), ids.get(leg.to()), leg.amount()))
                    .toList(), l.key()));  // a group is not one reversible transaction
            case CreateHold c -> holdIds.put(c.key(), holds.create(new CreateHoldRequest(
                    ids.get(c.from()), ids.get(c.to()), c.amount(), c.key(), null)).id());
            case PostHold p -> holds.post(holdId(p.holdKey(), holdIds), p.amount());
            case VoidHold v -> holds.voidHold(holdId(v.holdKey(), holdIds));
            case ExpireHold e -> holds.expire(holdId(e.holdKey(), holdIds), FAR_FUTURE);
        }
    }

    /** Unknown holds get a random id, so the service reports them as not found. */
    private static UUID holdId(String key, Map<String, UUID> holdIds) {
        return holdIds.getOrDefault(key, UUID.nameUUIDFromBytes(key.getBytes()));
    }

    private String compareState(Model model, List<UUID> ids) {
        for (int i = 0; i < ACCOUNTS; i++) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT balance, pending_debits, pending_credits FROM accounts WHERE id = ?", ids.get(i));
            if (differs(row.get("balance"), model.balance[i])
                    || differs(row.get("pending_debits"), model.pendingDebits[i])
                    || differs(row.get("pending_credits"), model.pendingCredits[i]))
                return "account %d is %s but the model says balance=%s pendingDebits=%s pendingCredits=%s"
                        .formatted(i, row, model.balance[i], model.pendingDebits[i], model.pendingCredits[i]);
        }
        return null;
    }

    private static boolean differs(Object db, BigDecimal expected) {
        return ((BigDecimal) db).compareTo(expected) != 0;
    }

    /** Greedily removes operations while the sequence still fails. */
    private List<Op> shrink(List<Op> ops) {
        List<Op> current = new ArrayList<>(ops);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int chunk = Math.max(1, current.size() / 2); chunk >= 1; chunk /= 2) {
                for (int i = 0; i + chunk <= current.size(); ) {
                    List<Op> candidate = new ArrayList<>(current.subList(0, i));
                    candidate.addAll(current.subList(i + chunk, current.size()));
                    if (!candidate.isEmpty() && run(candidate) != null) {
                        current = candidate;
                        progress = true;
                    } else {
                        i += chunk;
                    }
                }
            }
        }
        return current;
    }

    // ---- The test ------------------------------------------------------------

    @Test
    void randomOperationSequencesMatchTheModel() {
        String fixedSeed = System.getProperty("model.seed");
        int sequences = Integer.getInteger("model.sequences", 60);
        int length = Integer.getInteger("model.length", 80);
        long baseSeed = fixedSeed != null ? Long.parseLong(fixedSeed) : System.nanoTime();
        int count = fixedSeed != null ? 1 : sequences;
        int operations = 0;
        for (int n = 0; n < count; n++) {
            long seed = baseSeed + n;
            List<Op> ops = generate(seed, length);
            Failure failure = run(ops);
            operations += ops.size();
            if (failure != null) {
                List<Op> minimal = shrink(ops);
                Failure minimalFailure = run(minimal);
                StringBuilder message = new StringBuilder()
                        .append("Sequence with seed ").append(seed).append(" failed at step ").append(failure.step())
                        .append(": ").append(failure.message())
                        .append("\nReproduce with -Dmodel.seed=").append(seed).append(" -Dmodel.length=").append(length)
                        .append("\nShrunk to ").append(minimal.size()).append(" operations")
                        .append(minimalFailure == null ? "" : " (" + minimalFailure.message() + ")").append(":");
                for (Op op : minimal) message.append("\n  ").append(op);
                fail(message.toString());
            }
        }
        System.out.printf("[model] %d sequences, %d operations, all matched the model (base seed %d)%n",
                count, operations, baseSeed);
    }
}
