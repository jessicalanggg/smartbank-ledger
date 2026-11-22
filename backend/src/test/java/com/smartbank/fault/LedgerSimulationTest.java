package com.smartbank.fault;

import com.smartbank.account.AccountNotFoundException;
import com.smartbank.agent.AgentPaymentNotFoundException;
import com.smartbank.agent.AgentPaymentService;
import com.smartbank.agent.AgentPrincipal;
import com.smartbank.agent.MandateNotFoundException;
import com.smartbank.agent.MandateService;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Seeded simulation of the bank against an independent model.
 *
 * One seed determines a whole run: the operations (deposits, withdrawals, fees,
 * transfers, linked transfers, reversals, holds and their post/void/expiry, exact
 * retries, conflicting key reuse, and AI agents: mandates, payments within and over
 * their limits, approvals, declines, revocations and expired approvals), and the
 * faults injected into them:
 *
 * - CRASH: the request fails inside its database transaction, after writing
 *   everything and just before commit, so nothing may change; the client retries later.
 * - LOST_RESPONSE: the request commits but the client never sees the answer; it
 *   retries later with the same key, and must get the same result without anything
 *   happening twice.
 *
 * Requests run one at a time, so a seed replays the same run exactly; the test runs
 * its first seed twice and compares the two traces. After every step the outcome,
 * every balance and pending amount, every agent payment's status and every mandate's
 * spending must match the model, and the books are reconciled every few steps and at
 * the end (including the hash chain and the agent spending checks).
 *
 * On a mismatch the run is shrunk to the fewest steps that still fail and reported
 * with its seed. Reproduce with -Dsim.seed=<seed>. More coverage:
 * -Dsim.runs=500 -Dsim.length=200.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {"smartbank.holds.expiry-interval=PT1H", "smartbank.ledger.seal-interval=PT1H"})
class LedgerSimulationTest {
    private static final int ACCOUNTS = 4;
    private static final BigDecimal OPENING = new BigDecimal("100.00");
    private static final Instant FAR_FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired BankingService banking;
    @Autowired HoldService holds;
    @Autowired MandateService mandateService;
    @Autowired AgentPaymentService agentPayments;
    @Autowired FaultInjector faults;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;

    // ---- Operations ----------------------------------------------------------

    /** OK, REJECTED (a business rule), NOT_FOUND, or the agent payment's status after an agent operation. */
    enum Outcome { OK, REJECTED, NOT_FOUND, POSTED, PENDING_APPROVAL, BLOCKED, APPROVED, DECLINED, EXPIRED }

    sealed interface Op permits Deposit, Withdraw, Fee, Transfer, Linked, Reverse, CreateHold, PostHold, VoidHold, ExpireHold,
            CreateMandate, AgentPay, Approve, Decline, Revoke, ExpireApproval { }
    record Deposit(int account, BigDecimal amount, String key) implements Op { }
    record Withdraw(int account, BigDecimal amount, String key) implements Op { }
    record Fee(int account, BigDecimal amount, String key) implements Op { }
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
    /** A mandate on one account; payees empty means any. Referred to by ref. */
    record CreateMandate(String ref, int account, BigDecimal perPayment, BigDecimal daily, Set<Integer> payees, boolean ask)
            implements Op { }
    record AgentPay(String mandateRef, int payee, BigDecimal amount, String key) implements Op { }
    record Approve(String payKey) implements Op { }
    record Decline(String payKey) implements Op { }
    record Revoke(String mandateRef) implements Op { }
    /** The approval window of a payment waiting for approval passes (its hold expires). */
    record ExpireApproval(String payKey) implements Op { }

    enum Fault { NONE, CRASH, LOST_RESPONSE }
    /** One request of the run and the fault injected into it. */
    record Step(Op op, Fault fault) { }

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
        final Map<String, ModelMandate> mandates = new HashMap<>();
        /** Agent payments by request key, only those the bank recorded (not ones that failed with an error). */
        final Map<String, ModelPayment> payments = new HashMap<>();

        static final class ModelHold {
            final CreateHold create;
            String status = "PENDING";
            BigDecimal posted;
            ModelHold(CreateHold create) { this.create = create; }
            ModelHold copy() { ModelHold h = new ModelHold(create); h.status = status; h.posted = posted; return h; }
        }

        static final class ModelMandate {
            final CreateMandate create;
            boolean revoked;
            /** Spent today: paid within the limits plus approved above them. */
            BigDecimal used = BigDecimal.ZERO;
            ModelMandate(CreateMandate create) { this.create = create; }
            ModelMandate copy() { ModelMandate m = new ModelMandate(create); m.revoked = revoked; m.used = used; return m; }
        }

        static final class ModelPayment {
            final AgentPay pay;
            /** As stored: POSTED, PENDING_APPROVAL, BLOCKED, APPROVED, DECLINED or EXPIRED. */
            Outcome status;
            /** The hold behind a payment awaiting approval expired; it then reads as EXPIRED. */
            boolean holdExpired;
            ModelPayment(AgentPay pay, Outcome status) { this.pay = pay; this.status = status; }
            ModelPayment copy() { ModelPayment p = new ModelPayment(pay, status); p.holdExpired = holdExpired; return p; }
            Outcome shown() { return status == Outcome.PENDING_APPROVAL && holdExpired ? Outcome.EXPIRED : status; }
            boolean hasHold() { return status != Outcome.POSTED && status != Outcome.BLOCKED; }
        }

        /** A deep copy, so a step that crashes can be undone. */
        Model copy() {
            Model m = new Model();
            System.arraycopy(balance, 0, m.balance, 0, ACCOUNTS);
            System.arraycopy(pendingDebits, 0, m.pendingDebits, 0, ACCOUNTS);
            System.arraycopy(pendingCredits, 0, m.pendingCredits, 0, ACCOUNTS);
            m.moneyKeys.putAll(moneyKeys);
            m.reversed.putAll(reversed);
            m.resolvedReversals.putAll(resolvedReversals);
            holdsByKey.forEach((k, v) -> m.holdsByKey.put(k, v.copy()));
            mandates.forEach((k, v) -> m.mandates.put(k, v.copy()));
            payments.forEach((k, v) -> m.payments.put(k, v.copy()));
            return m;
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
                case Fee f -> {
                    Op prior = moneyKeys.get(f.key());
                    if (prior != null) return sameRequest(prior, f) ? Outcome.OK : Outcome.REJECTED;
                    if (available(f.account()).compareTo(f.amount()) < 0) return Outcome.REJECTED;
                    balance[f.account()] = balance[f.account()].subtract(f.amount());
                    moneyKeys.put(f.key(), f);
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
                    if (original instanceof Fee) return Outcome.REJECTED;  // fees are not reversible
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
                case CreateMandate c -> {
                    mandates.putIfAbsent(c.ref(), new ModelMandate(c));
                    return Outcome.OK;
                }
                case AgentPay p -> {
                    ModelMandate m = mandates.get(p.mandateRef());
                    if (m == null) return Outcome.NOT_FOUND;
                    ModelPayment prior = payments.get(p.key());
                    if (prior != null)
                        return prior.pay.mandateRef().equals(p.mandateRef()) && prior.pay.payee() == p.payee()
                                && prior.pay.amount().compareTo(p.amount()) == 0 ? prior.shown() : Outcome.REJECTED;
                    int from = m.create.account();
                    if (m.revoked || p.payee() == from) return Outcome.REJECTED;
                    if (!m.create.payees().isEmpty() && !m.create.payees().contains(p.payee()))
                        return record(p, Outcome.BLOCKED);
                    boolean over = p.amount().compareTo(m.create.perPayment()) > 0
                            || m.used.add(p.amount()).compareTo(m.create.daily()) > 0;
                    if (!over) {
                        if (available(from).compareTo(p.amount()) < 0) return Outcome.REJECTED;
                        balance[from] = balance[from].subtract(p.amount());
                        balance[p.payee()] = balance[p.payee()].add(p.amount());
                        m.used = m.used.add(p.amount());
                        return record(p, Outcome.POSTED);
                    }
                    if (!m.create.ask()) return record(p, Outcome.BLOCKED);
                    long waiting = payments.values().stream().filter(x -> x.pay.mandateRef().equals(p.mandateRef())
                            && x.status == Outcome.PENDING_APPROVAL && !x.holdExpired).count();
                    if (waiting >= 5) return record(p, Outcome.BLOCKED);
                    if (available(from).compareTo(p.amount()) < 0) return Outcome.REJECTED;
                    pendingDebits[from] = pendingDebits[from].add(p.amount());
                    pendingCredits[p.payee()] = pendingCredits[p.payee()].add(p.amount());
                    return record(p, Outcome.PENDING_APPROVAL);
                }
                case Approve a -> {
                    ModelPayment p = payments.get(a.payKey());
                    if (p == null) return Outcome.NOT_FOUND;
                    if (p.status == Outcome.APPROVED) return Outcome.APPROVED;
                    if (p.status != Outcome.PENDING_APPROVAL) return Outcome.REJECTED;
                    if (p.holdExpired) { p.status = Outcome.EXPIRED; return Outcome.REJECTED; }
                    ModelMandate m = mandates.get(p.pay.mandateRef());
                    releaseAgentHold(p);
                    balance[m.create.account()] = balance[m.create.account()].subtract(p.pay.amount());
                    balance[p.pay.payee()] = balance[p.pay.payee()].add(p.pay.amount());
                    m.used = m.used.add(p.pay.amount());
                    p.status = Outcome.APPROVED;
                    return Outcome.APPROVED;
                }
                case Decline d -> {
                    ModelPayment p = payments.get(d.payKey());
                    if (p == null) return Outcome.NOT_FOUND;
                    if (p.status == Outcome.DECLINED) return Outcome.DECLINED;
                    if (p.status != Outcome.PENDING_APPROVAL) return Outcome.REJECTED;
                    declineOrExpire(p);
                    return p.status;
                }
                case Revoke r -> {
                    ModelMandate m = mandates.get(r.mandateRef());
                    if (m == null) return Outcome.NOT_FOUND;
                    if (!m.revoked) {
                        m.revoked = true;
                        payments.values().stream()
                                .filter(p -> p.pay.mandateRef().equals(r.mandateRef()) && p.status == Outcome.PENDING_APPROVAL)
                                .forEach(this::declineOrExpire);
                    }
                    return Outcome.OK;
                }
                case ExpireApproval e -> {
                    ModelPayment p = payments.get(e.payKey());
                    if (p == null || !p.hasHold()) return Outcome.NOT_FOUND;
                    if (p.status == Outcome.PENDING_APPROVAL && !p.holdExpired) {
                        releaseAgentHold(p);
                        p.holdExpired = true;
                    }
                    return Outcome.OK;
                }
            }
        }

        private Outcome record(AgentPay p, Outcome status) {
            payments.put(p.key(), new ModelPayment(p, status));
            return status;
        }

        private void releaseAgentHold(ModelPayment p) {
            int from = mandates.get(p.pay.mandateRef()).create.account();
            pendingDebits[from] = pendingDebits[from].subtract(p.pay.amount());
            pendingCredits[p.pay.payee()] = pendingCredits[p.pay.payee()].subtract(p.pay.amount());
        }

        /** Declining a payment whose approval window already passed leaves it expired. */
        private void declineOrExpire(ModelPayment p) {
            if (p.holdExpired) {
                p.status = Outcome.EXPIRED;
            } else {
                releaseAgentHold(p);
                p.status = Outcome.DECLINED;
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
                case Fee x when b instanceof Fee y -> x.account() == y.account() && x.amount().compareTo(y.amount()) == 0;
                case Transfer x when b instanceof Transfer y -> x.from() == y.from() && x.to() == y.to() && x.amount().compareTo(y.amount()) == 0;
                case CreateHold x when b instanceof CreateHold y -> x.from() == y.from() && x.to() == y.to() && x.amount().compareTo(y.amount()) == 0;
                case Linked x when b instanceof Linked y -> sameLegs(x.legs(), y.legs());
                default -> false;
            };
        }
    }

    // ---- Generation ----------------------------------------------------------

    static List<Step> generate(long seed, int length) {
        Random r = new Random(seed);
        List<Step> steps = new ArrayList<>();
        List<Op> moneyOps = new ArrayList<>();
        List<CreateHold> holdOps = new ArrayList<>();
        List<CreateMandate> mandateOps = new ArrayList<>();
        List<AgentPay> payOps = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int kind = r.nextInt(100);
            int a = r.nextInt(ACCOUNTS), b = r.nextInt(ACCOUNTS);
            BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 8_001), 2);  // 0.01 to 80.00
            String key = "s" + seed + "-k" + i;
            Op op;
            if (kind < 7 && !moneyOps.isEmpty()) {
                op = moneyOps.get(r.nextInt(moneyOps.size()));  // exact retry
            } else if (kind < 10 && !moneyOps.isEmpty()) {
                String reused = keyOf(moneyOps.get(r.nextInt(moneyOps.size())));  // conflicting reuse
                op = new Transfer(a, b, amount, reused);
            } else if (kind < 14 && !moneyOps.isEmpty()) {
                // Reverse an earlier operation (sometimes one that failed or is itself a reversal).
                String target = keyOf(moneyOps.get(r.nextInt(moneyOps.size())));
                if (r.nextInt(15) == 0) target = "missing-" + key;
                op = new Reverse(target, r.nextBoolean() ? null : BigDecimal.valueOf(r.nextInt(1, 4_001), 2), key);
            } else if (kind < 19) {
                op = new Deposit(a, amount, key);
            } else if (kind < 26) {
                op = new Withdraw(a, amount, key);
            } else if (kind < 28) {
                op = new Fee(a, BigDecimal.valueOf(r.nextInt(1, 1_001), 2), key);
            } else if (kind < 37) {
                op = new Transfer(a, b, amount, key);  // a == b sometimes, which must be rejected
            } else if (kind < 42) {
                List<Leg> legs = new ArrayList<>();
                for (int n = r.nextInt(2, 5); n > 0; n--) {
                    int from = r.nextInt(ACCOUNTS), to = r.nextInt(ACCOUNTS);
                    if (from == to && r.nextInt(4) != 0) to = (to + 1) % ACCOUNTS;  // mostly valid legs
                    legs.add(new Leg(from, to, BigDecimal.valueOf(r.nextInt(1, 8_001), 2)));
                }
                op = new Linked(legs, key);
            } else if (kind < 52) {
                CreateHold c = r.nextInt(8) == 0 && !holdOps.isEmpty()
                        ? holdOps.get(r.nextInt(holdOps.size()))  // retry of a hold
                        : new CreateHold(a, b, amount, "h" + key);
                holdOps.add(c);
                op = c;
            } else if (kind < 60) {
                if (holdOps.isEmpty()) {
                    op = new Deposit(a, amount, key);
                } else {
                    String holdKey = r.nextInt(20) == 0 ? "missing-" + key : holdOps.get(r.nextInt(holdOps.size())).key();
                    op = switch (kind % 3) {
                        case 0 -> new PostHold(holdKey, r.nextBoolean() ? null : amount);
                        case 1 -> new VoidHold(holdKey);
                        default -> new ExpireHold(holdKey);
                    };
                }
            } else if (kind < 64 || mandateOps.isEmpty()) {
                BigDecimal per = BigDecimal.valueOf(r.nextInt(500, 4_001), 2);  // 5.00 to 40.00
                BigDecimal daily = per.multiply(BigDecimal.valueOf(r.nextInt(1, 4)));
                Set<Integer> payees = new TreeSet<>();
                if (r.nextBoolean())
                    for (int n = r.nextInt(1, ACCOUNTS); n > 0; n--) payees.add(r.nextInt(ACCOUNTS));
                payees.remove(a);  // a mandate cannot list its own account
                CreateMandate c = new CreateMandate("m" + i, a, per, daily, payees, r.nextInt(10) < 7);
                mandateOps.add(c);
                op = c;
            } else if (kind < 85 || payOps.isEmpty()) {
                CreateMandate m = mandateOps.get(r.nextInt(mandateOps.size()));
                AgentPay pay = r.nextInt(10) == 0 && !payOps.isEmpty()
                        ? payOps.get(r.nextInt(payOps.size()))  // an agent retrying
                        : new AgentPay(m.ref(), r.nextInt(ACCOUNTS), BigDecimal.valueOf(r.nextInt(1, 6_001), 2), "p" + key);
                payOps.add(pay);
                op = pay;
            } else {
                String payKey = r.nextInt(20) == 0 ? "missing-" + key : payOps.get(r.nextInt(payOps.size())).key();
                if (kind < 90) op = new Approve(payKey);
                else if (kind < 94) op = new Decline(payKey);
                else if (kind < 96) op = new Revoke(mandateOps.get(r.nextInt(mandateOps.size())).ref());
                else op = new ExpireApproval(payKey);
            }
            if (op instanceof Deposit || op instanceof Withdraw || op instanceof Fee || op instanceof Transfer
                    || op instanceof Linked || op instanceof Reverse) moneyOps.add(op);
            // Faults: any request may crash before commit or lose its response after it.
            int f = r.nextInt(100);
            Fault fault = op instanceof CreateMandate ? Fault.NONE
                    : f < 6 ? Fault.CRASH : f < 12 ? Fault.LOST_RESPONSE : Fault.NONE;
            steps.add(new Step(op, fault));
        }
        return steps;
    }

    private static String keyOf(Op op) {
        return switch (op) {
            case Deposit d -> d.key();
            case Withdraw w -> w.key();
            case Fee f -> f.key();
            case Transfer t -> t.key();
            case Linked l -> l.key();
            case Reverse v -> v.key();
            default -> throw new IllegalArgumentException(op.toString());
        };
    }

    // ---- Execution -----------------------------------------------------------

    /** Why a run failed, or null if it passed. */
    record Failure(int step, String message) { }
    /** What a run did, step by step, so two runs of one seed can be compared. */
    record Run(Failure failure, List<String> trace, int crashes, int lostResponses) { }
    /** What a request returned: its outcome and the id of what it created or changed, if any. */
    record Result(Outcome outcome, String id) { }

    /** Ids the bank gave to what the run refers to by key. */
    record Ids(List<UUID> accounts, Map<String, UUID> holds, Map<String, UUID> transactions,
               Map<String, UUID> mandates, Map<String, UUID> payments) { }

    /** The customer who owns every account in the run, and gives the agents their mandates. */
    private UUID owner;

    private List<UUID> resetAccounts() {
        LedgerTestData.truncateLedger(jdbc);
        jdbc.update("DELETE FROM accounts WHERE account_number LIKE 'MODEL-%'");
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        if (owner == null) {
            owner = UUID.randomUUID();
            jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')",
                    owner, owner + "@sim.local");
        }
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = LedgerTestData.openAccount(jdbc, "MODEL-" + i, OPENING, "CAD");
            jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
            ids.add(id);
        }
        return ids;
    }

    private Run run(List<Step> steps) {
        Ids ids = new Ids(resetAccounts(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        Model model = new Model();
        List<String> trace = new ArrayList<>();
        // The steps to run, with clients' retries inserted as faults happen.
        List<Step> work = new ArrayList<>(steps);
        // A retry after a lost response must return what the lost response would have.
        Map<Step, String> mustReturn = new IdentityHashMap<>();
        int crashes = 0, lost = 0;
        for (int i = 0; i < work.size(); i++) {
            Step step = work.get(i);
            Op op = step.op();
            Model next = model.copy();
            Outcome expected = next.apply(op);
            Result result = null;
            boolean crashed = false, fired;
            faults.arm(step.fault() == Fault.CRASH);
            try {
                result = execute(op, ids);
            } catch (HoldNotFoundException | AccountNotFoundException | TransactionNotFoundException
                     | MandateNotFoundException | AgentPaymentNotFoundException e) {
                result = new Result(Outcome.NOT_FOUND, null);
            } catch (BankingException e) {
                result = new Result(Outcome.REJECTED, null);
            } catch (RuntimeException e) {
                if (!isCrash(e)) return failed(i, op + " threw " + e, trace, crashes, lost);
                crashed = true;
            } finally {
                fired = faults.disarm();
            }
            if (fired != crashed)
                return failed(i, op + (fired ? " crashed but reported success" : " failed without a crash"), trace, crashes, lost);

            if (crashed) {
                // Nothing may have changed; the client tries again a little later.
                crashes++;
                trace.add(i + " CRASH " + op);
                String mismatch = compareState(model, ids);
                if (mismatch != null) return failed(i, "after a crash in " + op + ": " + mismatch, trace, crashes, lost);
                work.add(Math.min(i + 3, work.size()), new Step(op, Fault.NONE));
                continue;
            }
            if (result.outcome() != expected)
                return failed(i, op + ": expected " + expected + " but was " + result.outcome(), trace, crashes, lost);
            model = next;
            trace.add(i + " " + result.outcome() + " " + op);
            String original = mustReturn.get(step);
            if (original != null && result.id() != null && !original.equals(result.id()))
                return failed(i, "retrying " + op + " after a lost response returned " + result.id()
                        + " instead of " + original, trace, crashes, lost);
            if (step.fault() == Fault.LOST_RESPONSE) {
                // The answer never reached the client; it sends the same request again later.
                lost++;
                Step retry = new Step(op, Fault.NONE);
                if (result.id() != null) mustReturn.put(retry, result.id());
                work.add(Math.min(i + 3, work.size()), retry);
            }
            String mismatch = compareState(model, ids);
            if (mismatch != null) return failed(i, "after " + op + ": " + mismatch, trace, crashes, lost);
            if ((i + 1) % 25 == 0) {
                var report = reconciliation.run();
                if (!report.isClean()) return failed(i, "books out of balance: " + report, trace, crashes, lost);
            }
        }
        var report = reconciliation.run();
        if (!report.isClean()) return failed(work.size(), "books out of balance: " + report, trace, crashes, lost);
        return new Run(null, trace, crashes, lost);
    }

    private static Run failed(int step, String message, List<String> trace, int crashes, int lost) {
        return new Run(new Failure(step, message), trace, crashes, lost);
    }

    private static boolean isCrash(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SimulatedCrash) return true;
        return false;
    }

    private Result execute(Op op, Ids ids) {
        List<UUID> accounts = ids.accounts();
        Map<String, UUID> txIds = ids.transactions();
        switch (op) {
            case Deposit d -> {
                return ok(txIds, d.key(), banking.deposit(accounts.get(d.account()), new MoneyRequest(d.amount(), d.key())).id());
            }
            case Fee f -> {
                return ok(txIds, f.key(), banking.chargeFee(accounts.get(f.account()), new MoneyRequest(f.amount(), f.key())).id());
            }
            case Withdraw w -> {
                return ok(txIds, w.key(), banking.withdraw(accounts.get(w.account()), new MoneyRequest(w.amount(), w.key())).id());
            }
            case Transfer t -> {
                return ok(txIds, t.key(), banking.transfer(
                        new TransferRequest(accounts.get(t.from()), accounts.get(t.to()), t.amount(), t.key())).id());
            }
            case Reverse v -> {
                return ok(txIds, v.key(), banking.reverse(
                        txIds.getOrDefault(v.originalKey(), UUID.nameUUIDFromBytes(v.originalKey().getBytes())),
                        new ReversalRequest(v.amount(), v.key())).id());
            }
            case Linked l -> {
                // A group is not one reversible transaction, so it is not recorded by key.
                return new Result(Outcome.OK, banking.linkedTransfer(new LinkedTransferRequest(l.legs().stream()
                        .map(leg -> new LinkedTransferRequest.Leg(accounts.get(leg.from()), accounts.get(leg.to()), leg.amount()))
                        .toList(), l.key())).linkId().toString());
            }
            case CreateHold c -> {
                UUID id = holds.create(new CreateHoldRequest(accounts.get(c.from()), accounts.get(c.to()), c.amount(), c.key(), null)).id();
                ids.holds().put(c.key(), id);
                return new Result(Outcome.OK, id.toString());
            }
            case PostHold p -> {
                return new Result(Outcome.OK, holds.post(holdId(p.holdKey(), ids.holds()), p.amount()).id().toString());
            }
            case VoidHold v -> {
                return new Result(Outcome.OK, holds.voidHold(holdId(v.holdKey(), ids.holds())).id().toString());
            }
            case ExpireHold e -> {
                holds.expire(holdId(e.holdKey(), ids.holds()), FAR_FUTURE);
                return new Result(Outcome.OK, null);
            }
            case CreateMandate c -> {
                if (!ids.mandates().containsKey(c.ref())) {
                    var created = mandateService.create(owner, new MandateService.CreateMandate(accounts.get(c.account()),
                            "Simulated agent " + c.ref(), c.perPayment(), c.daily(),
                            c.payees().stream().map(p -> "MODEL-" + p).toList(), 30, c.ask()));
                    ids.mandates().put(c.ref(), created.mandate().id());
                }
                return new Result(Outcome.OK, ids.mandates().get(c.ref()).toString());
            }
            case AgentPay p -> {
                UUID mandate = ids.mandates().getOrDefault(p.mandateRef(), UUID.nameUUIDFromBytes(p.mandateRef().getBytes()));
                var payment = agentPayments.pay(new AgentPrincipal(mandate, owner, null),
                        new AgentPaymentService.PaymentRequest("MODEL-" + p.payee(), p.amount(), "simulated purchase"), p.key());
                ids.payments().put(p.key(), payment.id());
                return new Result(agentOutcome(payment.status()), payment.id().toString());
            }
            case Approve a -> {
                var payment = agentPayments.approve(owner, paymentId(a.payKey(), ids));
                return new Result(agentOutcome(payment.status()), payment.id().toString());
            }
            case Decline d -> {
                var payment = agentPayments.decline(owner, paymentId(d.payKey(), ids));
                return new Result(agentOutcome(payment.status()), payment.id().toString());
            }
            case Revoke r -> {
                agentPayments.revoke(owner, ids.mandates().getOrDefault(r.mandateRef(), UUID.nameUUIDFromBytes(r.mandateRef().getBytes())));
                return new Result(Outcome.OK, null);
            }
            case ExpireApproval e -> {
                UUID payment = ids.payments().get(e.payKey());
                UUID hold = payment == null ? null
                        : jdbc.queryForObject("SELECT hold_id FROM agent_payments WHERE id = ?", UUID.class, payment);
                if (hold == null) throw new HoldNotFoundException(UUID.nameUUIDFromBytes(e.payKey().getBytes()));
                holds.expire(hold, FAR_FUTURE);
                return new Result(Outcome.OK, null);
            }
        }
    }

    private static Result ok(Map<String, UUID> txIds, String key, UUID id) {
        txIds.putIfAbsent(key, id);
        return new Result(Outcome.OK, id.toString());
    }

    /** The API's REJECTED status for an agent payment is the model's BLOCKED (REJECTED means an error here). */
    private static Outcome agentOutcome(String status) {
        return status.equals("REJECTED") ? Outcome.BLOCKED : Outcome.valueOf(status);
    }

    private static UUID paymentId(String key, Ids ids) {
        return ids.payments().getOrDefault(key, UUID.nameUUIDFromBytes(key.getBytes()));
    }

    /** Unknown holds get a random id, so the service reports them as not found. */
    private static UUID holdId(String key, Map<String, UUID> holdIds) {
        return holdIds.getOrDefault(key, UUID.nameUUIDFromBytes(key.getBytes()));
    }

    private String compareState(Model model, Ids ids) {
        for (int i = 0; i < ACCOUNTS; i++) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT balance, pending_debits, pending_credits FROM accounts WHERE id = ?", ids.accounts().get(i));
            if (differs(row.get("balance"), model.balance[i])
                    || differs(row.get("pending_debits"), model.pendingDebits[i])
                    || differs(row.get("pending_credits"), model.pendingCredits[i]))
                return "account %d is %s but the model says balance=%s pendingDebits=%s pendingCredits=%s"
                        .formatted(i, row, model.balance[i], model.pendingDebits[i], model.pendingCredits[i]);
        }
        Integer recorded = jdbc.queryForObject("SELECT COUNT(*) FROM agent_payments", Integer.class);
        if (recorded != model.payments.size())
            return recorded + " agent payments recorded but the model has " + model.payments.size();
        for (var entry : model.payments.entrySet()) {
            UUID id = ids.payments().get(entry.getKey());
            Outcome actual = id == null ? null : agentOutcome(agentPayments.get(id).status());
            if (actual != entry.getValue().shown())
                return "agent payment " + entry.getKey() + " is " + actual + " but the model says " + entry.getValue().shown();
        }
        for (var entry : model.mandates.entrySet()) {
            BigDecimal used = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(auto_spent + approved_spent), 0) FROM agent_mandate_usage WHERE mandate_id = ?""",
                    BigDecimal.class, ids.mandates().get(entry.getKey()));
            if (used.compareTo(entry.getValue().used) != 0)
                return "mandate " + entry.getKey() + " spent " + used + " but the model says " + entry.getValue().used;
        }
        return null;
    }

    private static boolean differs(Object db, BigDecimal expected) {
        return ((BigDecimal) db).compareTo(expected) != 0;
    }

    /** Greedily removes steps while the run still fails. */
    private List<Step> shrink(List<Step> steps) {
        List<Step> current = new ArrayList<>(steps);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int chunk = Math.max(1, current.size() / 2); chunk >= 1; chunk /= 2) {
                for (int i = 0; i + chunk <= current.size(); ) {
                    List<Step> candidate = new ArrayList<>(current.subList(0, i));
                    candidate.addAll(current.subList(i + chunk, current.size()));
                    if (!candidate.isEmpty() && run(candidate).failure() != null) {
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
    void seededRunsWithInjectedFaultsMatchTheModel() {
        String fixedSeed = System.getProperty("sim.seed");
        int runs = Integer.getInteger("sim.runs", 40);
        int length = Integer.getInteger("sim.length", 100);
        long baseSeed = fixedSeed != null ? Long.parseLong(fixedSeed) : System.nanoTime();
        int count = fixedSeed != null ? 1 : runs;
        int steps = 0, crashes = 0, lost = 0;
        List<String> firstTrace = null;
        for (int n = 0; n < count; n++) {
            long seed = baseSeed + n;
            List<Step> generated = generate(seed, length);
            Run run = run(generated);
            if (run.failure() != null) {
                Failure failure = run.failure();
                List<Step> minimal = shrink(generated);
                Failure minimalFailure = run(minimal).failure();
                StringBuilder message = new StringBuilder()
                        .append("Run with seed ").append(seed).append(" failed at step ").append(failure.step())
                        .append(": ").append(failure.message())
                        .append("\nReproduce with -Dsim.seed=").append(seed).append(" -Dsim.length=").append(length)
                        .append("\nShrunk to ").append(minimal.size()).append(" steps")
                        .append(minimalFailure == null ? "" : " (" + minimalFailure.message() + ")").append(":");
                for (Step step : minimal) message.append("\n  ").append(step);
                fail(message.toString());
            }
            if (n == 0) firstTrace = run.trace();
            steps += run.trace().size();
            crashes += run.crashes();
            lost += run.lostResponses();
        }
        // The same seed must replay the same run: every step, fault and outcome.
        assertEquals(firstTrace, run(generate(baseSeed, length)).trace(), "Seed " + baseSeed + " did not replay identically");
        System.out.printf("[sim] %d runs, %d steps (%d injected crashes, %d lost responses), all matched the model; "
                + "seed %d replayed identically (base seed %d)%n", count, steps, crashes, lost, baseSeed, baseSeed);
    }

    // ---- Fault injection -------------------------------------------------------

    /** Thrown inside a request's database transaction, as if the server died before commit. */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash() { super("Simulated crash before commit"); }
    }

    /** Arms one crash for the next request. Background jobs are never crashed. */
    static final class FaultInjector {
        private final AtomicBoolean armed = new AtomicBoolean(), fired = new AtomicBoolean();

        void arm(boolean crash) {
            fired.set(false);
            armed.set(crash);
        }

        /** Disarms; returns whether the armed crash happened. */
        boolean disarm() {
            armed.set(false);
            return fired.get();
        }

        void maybeCrash() {
            if (!Thread.currentThread().getName().startsWith("scheduling") && armed.compareAndSet(true, false)) {
                fired.set(true);
                throw new SimulatedCrash();
            }
        }
    }

    /**
     * Crashes a request right after its last write: every posting, hold change and
     * agent payment applies its account changes as the final step of its transaction.
     */
    @Aspect
    static final class CrashBeforeCommit {
        private final FaultInjector faults;

        CrashBeforeCommit(FaultInjector faults) { this.faults = faults; }

        @Around("execution(* com.smartbank.transaction.PostingWriter.applyAccountChanges(..))"
                + " || execution(* com.smartbank.transaction.PostingWriter.applyBalanceChanges(..))")
        Object crashAfterWriting(ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            faults.maybeCrash();
            return result;
        }
    }

    @TestConfiguration
    static class Faults {
        @Bean FaultInjector faultInjector() { return new FaultInjector(); }
        @Bean CrashBeforeCommit crashBeforeCommit(FaultInjector faults) { return new CrashBeforeCommit(faults); }
    }
}
