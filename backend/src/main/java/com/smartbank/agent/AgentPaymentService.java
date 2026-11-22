package com.smartbank.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartbank.account.Account;
import com.smartbank.account.AccountRepository;
import com.smartbank.common.Currencies;
import com.smartbank.events.EventTypes;
import com.smartbank.events.OutboxWriter;
import com.smartbank.transaction.BankingException;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.CreateHoldRequest;
import com.smartbank.transaction.HoldExpiredException;
import com.smartbank.transaction.HoldResponse;
import com.smartbank.transaction.HoldService;
import com.smartbank.transaction.HoldStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments AI agents make under mandates, and their owners' approvals.
 *
 * A payment is decided and carried out in one database transaction:
 *
 * 1. The mandate is locked FOR SHARE: payments under it run side by side, but a
 *    revocation (which takes the row exclusively) waits for those in flight, and no
 *    payment starts after it. Once revoke returns, the agent cannot move money.
 * 2. The payee is checked against the mandate (currency, allowed list).
 * 3. Within the per-payment limit, the amount is added to the day's usage by one
 *    conditional upsert that only applies if the day stays within the daily limit.
 *    It locks that usage row until commit, so simultaneous payments are counted one
 *    after another, and together can never exceed the limit. A trigger re-checks it.
 * 4. Within both limits, the transfer posts at once, in the same transaction. Over a
 *    limit, the payment is rejected, or held for the owner to approve if the mandate
 *    asks. Either way the attempt is recorded.
 *
 * Lock order, everywhere: mandate, agent payment, usage, hold, accounts (UUID order).
 */
@Service
public class AgentPaymentService {
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final BankingService banking;
    private final HoldService holds;
    private final OutboxWriter outbox;
    private final Currencies currencies;
    private final Clock clock;
    private final Duration approvalTtl;
    private final int maxPendingApprovals;

    public AgentPaymentService(JdbcTemplate jdbc, TransactionTemplate transactions, AccountRepository accounts,
                               BankingService banking, HoldService holds, OutboxWriter outbox, Currencies currencies,
                               Clock clock,
                               @Value("${smartbank.agents.approval-ttl:PT24H}") Duration approvalTtl,
                               @Value("${smartbank.agents.max-pending-approvals:5}") int maxPendingApprovals) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.banking = banking;
        this.holds = holds;
        this.outbox = outbox;
        this.currencies = currencies;
        this.clock = clock;
        this.approvalTtl = approvalTtl;
        this.maxPendingApprovals = maxPendingApprovals;
    }

    public record PaymentRequest(String toAccountNumber, BigDecimal amount, String intent) { }

    /**
     * POSTED: paid within the limits. PENDING_APPROVAL: over a limit, money held until
     * the owner approves (APPROVED) or declines (DECLINED), or the approval window
     * passes (EXPIRED). REJECTED: not allowed, nothing moved; reason says why.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentPayment(UUID id, UUID mandateId, String status, String reason, String payeeAccountNumber,
                               BigDecimal amount, String currency, String intent, UUID transactionId, UUID holdId,
                               Instant approvalExpiresAt, Instant createdAt, Instant decidedAt) { }

    private record MandateRow(UUID id, UUID userId, UUID accountId, String currency, BigDecimal perPaymentLimit,
                              BigDecimal dailyLimit, boolean askAboveLimit, Instant expiresAt, Instant revokedAt) { }

    // --- Paying -------------------------------------------------------------------

    /** Makes a payment for the agent; idempotent by {@code requestKey}. */
    public AgentPayment pay(AgentPrincipal agent, PaymentRequest request, String requestKey) {
        if (request.intent() == null || request.intent().isBlank())
            throw new IllegalArgumentException("A payment needs an intent: what it is for");
        for (int attempt = 1; ; attempt++) {
            var previous = findByRequestKey(requestKey);
            if (previous.isPresent()) return replay(previous.get(), agent, request);
            try {
                return transactions.execute(status -> payInTransaction(agent, request, requestKey));
            } catch (DataIntegrityViolationException e) {
                var winner = findByRequestKey(requestKey);
                if (winner.isPresent()) return replay(winner.get(), agent, request);
                throw e;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_DEADLOCK_RETRIES) throw e;
            }
        }
    }

    private AgentPayment payInTransaction(AgentPrincipal agent, PaymentRequest request, String key) {
        MandateRow m = lockMandate(agent.mandateId(), "FOR SHARE");
        Instant now = now();
        if (m.revokedAt() != null || !now.isBefore(m.expiresAt()))
            throw new BankingException("This mandate is revoked or expired");
        // A simultaneous identical request may have committed while we waited for the lock.
        var previous = findByRequestKey(key);
        if (previous.isPresent()) return replay(previous.get(), agent, request);

        Account payee = accounts.findByAccountNumber(request.toAccountNumber().trim())
                .orElseThrow(() -> new BankingException("Payee account not found"));
        if (payee.getId().equals(m.accountId())) throw new BankingException("An agent cannot pay its own account");
        if (!payee.getCurrency().equals(m.currency()))
            throw new BankingException("The payee's account is not in " + m.currency());
        BigDecimal amount = request.amount();
        currencies.requireValidAmount(amount, m.currency());
        String intent = request.intent().trim();
        UUID id = UUID.randomUUID();

        Boolean allowed = jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM agent_mandate_payees WHERE mandate_id = ?)
                    OR EXISTS (SELECT 1 FROM agent_mandate_payees WHERE mandate_id = ? AND account_id = ?)""",
                Boolean.class, m.id(), m.id(), payee.getId());
        if (!Boolean.TRUE.equals(allowed))
            return reject(id, m, key, payee.getId(), amount, intent, "The payee is not on this mandate's allowed list", now);

        String overLimit;
        if (amount.compareTo(m.perPaymentLimit()) > 0) {
            overLimit = "Over the per-payment limit of " + m.perPaymentLimit().toPlainString() + " " + m.currency();
        } else {
            LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
            // The limit check and the spend are one statement; see the class comment.
            boolean withinDaily = !jdbc.query("""
                    INSERT INTO agent_mandate_usage AS u (mandate_id, day, auto_spent) VALUES (?, ?, ?)
                    ON CONFLICT (mandate_id, day) DO UPDATE SET auto_spent = u.auto_spent + EXCLUDED.auto_spent
                    WHERE u.auto_spent + u.approved_spent + EXCLUDED.auto_spent <= ?
                    RETURNING auto_spent""", (rs, n) -> rs.getBigDecimal(1), m.id(), day, amount, m.dailyLimit()).isEmpty();
            if (withinDaily) {
                var tx = banking.postAgentTransfer(m.accountId(), payee.getId(), amount, key, m.id());
                jdbc.update("""
                        INSERT INTO agent_payments (id, mandate_id, request_key, payee_account_id, amount, currency, intent,
                            status, transaction_id, usage_day, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?, ?)""",
                        id, m.id(), key, payee.getId(), amount, m.currency(), intent, tx.id(), day, Timestamp.from(now));
                return publish(EventTypes.AGENT_PAYMENT_POSTED, id, m.accountId());
            }
            overLimit = "Over the daily limit of " + m.dailyLimit().toPlainString() + " " + m.currency();
        }

        if (!m.askAboveLimit())
            return reject(id, m, key, payee.getId(), amount, intent, overLimit, now);
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_payments p JOIN holds h ON h.id = p.hold_id
                WHERE p.mandate_id = ? AND p.status = 'PENDING_APPROVAL' AND h.status = 'PENDING' AND h.expires_at > ?""",
                Integer.class, m.id(), Timestamp.from(now));
        if (pending != null && pending >= maxPendingApprovals)
            return reject(id, m, key, payee.getId(), amount, intent,
                    overLimit + ", and " + maxPendingApprovals + " payments already await approval", now);
        // Reserve the money now, so an approval cannot fail for lack of funds.
        HoldResponse hold = holds.createInCurrentTransaction(
                new CreateHoldRequest(m.accountId(), payee.getId(), amount, key, approvalTtl.toSeconds()));
        jdbc.update("""
                INSERT INTO agent_payments (id, mandate_id, request_key, payee_account_id, amount, currency, intent,
                    status, reason, hold_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?)""",
                id, m.id(), key, payee.getId(), amount, m.currency(), intent, overLimit, hold.id(), Timestamp.from(now));
        return publish(EventTypes.AGENT_PAYMENT_APPROVAL_REQUESTED, id, m.accountId());
    }

    private AgentPayment reject(UUID id, MandateRow m, String key, UUID payee, BigDecimal amount, String intent,
                                String reason, Instant now) {
        jdbc.update("""
                INSERT INTO agent_payments (id, mandate_id, request_key, payee_account_id, amount, currency, intent,
                    status, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REJECTED', ?, ?)""",
                id, m.id(), key, payee, amount, m.currency(), intent, reason, Timestamp.from(now));
        return publish(EventTypes.AGENT_PAYMENT_REJECTED, id, m.accountId());
    }

    private static AgentPayment replay(AgentPayment prior, AgentPrincipal agent, PaymentRequest request) {
        if (!prior.mandateId().equals(agent.mandateId()) || prior.amount().compareTo(request.amount()) != 0
                || !prior.payeeAccountNumber().equals(request.toAccountNumber().trim()))
            throw new BankingException("Request key was already used for a different operation");
        return prior;
    }

    // --- Owner decisions --------------------------------------------------------------

    /** The owner approves a payment over the limits: the held money is paid. */
    public AgentPayment approve(UUID userId, UUID paymentId) {
        Boolean expired = transactions.execute(status -> {
            UUID mandateId = lockForDecision(userId, paymentId);
            String current = lockedStatus(paymentId);
            if ("APPROVED".equals(current)) return false;
            if (!"PENDING_APPROVAL".equals(current))
                throw new BankingException("This payment was already " + current.toLowerCase().replace('_', ' '));
            var p = jdbc.queryForObject("SELECT hold_id, amount FROM agent_payments WHERE id = ?",
                    (rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getBigDecimal(2) }, paymentId);
            Instant now = now();
            HoldResponse posted;
            try {
                posted = holds.postForAgent((UUID) p[0], mandateId);
            } catch (HoldExpiredException e) {
                decide(paymentId, "EXPIRED", null, null, userId, now, null);
                return true;
            }
            LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO agent_mandate_usage AS u (mandate_id, day, approved_spent) VALUES (?, ?, ?)
                    ON CONFLICT (mandate_id, day) DO UPDATE SET approved_spent = u.approved_spent + EXCLUDED.approved_spent""",
                    mandateId, day, p[1]);
            decide(paymentId, "APPROVED", posted.postedTransactionId(), day, userId, now, null);
            publish(EventTypes.AGENT_PAYMENT_APPROVED, paymentId, posted.fromAccountId());
            return false;
        });
        if (Boolean.TRUE.equals(expired)) throw new BankingException("The approval window for this payment has passed");
        return get(paymentId);
    }

    /** The owner declines a payment over the limits: the held money is released. */
    public AgentPayment decline(UUID userId, UUID paymentId) {
        transactions.executeWithoutResult(status -> {
            lockForDecision(userId, paymentId);
            String current = lockedStatus(paymentId);
            if ("DECLINED".equals(current)) return;
            if (!"PENDING_APPROVAL".equals(current))
                throw new BankingException("This payment was already " + current.toLowerCase().replace('_', ' '));
            declineLocked(paymentId, userId, null);
        });
        return get(paymentId);
    }

    /**
     * Revokes the mandate: from the moment this returns, its key no longer works and
     * no payment under it can commit. Payments awaiting approval are declined.
     */
    public void revoke(UUID userId, UUID mandateId) {
        transactions.executeWithoutResult(status -> {
            MandateRow m = lockMandate(mandateId, "FOR NO KEY UPDATE");
            if (!m.userId().equals(userId)) throw new MandateNotFoundException(mandateId);
            if (m.revokedAt() != null) return;
            jdbc.update("UPDATE agent_mandates SET revoked_at = ? WHERE id = ?", Timestamp.from(now()), mandateId);
            List<UUID> pending = jdbc.queryForList("""
                    SELECT id FROM agent_payments WHERE mandate_id = ? AND status = 'PENDING_APPROVAL'
                    ORDER BY id FOR UPDATE""", UUID.class, mandateId);
            for (UUID payment : pending) declineLocked(payment, userId, "Mandate revoked");
        });
    }

    /** Releases the hold and records the decision; the payment row is locked by the caller. */
    private void declineLocked(UUID paymentId, UUID userId, String reason) {
        UUID holdId = jdbc.queryForObject("SELECT hold_id FROM agent_payments WHERE id = ?", UUID.class, paymentId);
        HoldResponse hold = holds.voidHold(holdId);
        // A hold that already expired stays expired; the payment did too.
        boolean expired = hold.status() == HoldStatus.EXPIRED;
        decide(paymentId, expired ? "EXPIRED" : "DECLINED", null, null, userId, now(), reason);
        if (!expired) publish(EventTypes.AGENT_PAYMENT_DECLINED, paymentId, hold.fromAccountId());
    }

    /** Checks the caller owns the payment's mandate and takes the mandate's share lock; returns the mandate. */
    private UUID lockForDecision(UUID userId, UUID paymentId) {
        var owner = jdbc.query("""
                SELECT m.id, m.user_id FROM agent_payments p JOIN agent_mandates m ON m.id = p.mandate_id WHERE p.id = ?""",
                (rs, n) -> new UUID[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class) }, paymentId);
        if (owner.isEmpty() || !owner.get(0)[1].equals(userId)) throw new AgentPaymentNotFoundException(paymentId);
        lockMandate(owner.get(0)[0], "FOR SHARE");
        return owner.get(0)[0];
    }

    private String lockedStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM agent_payments WHERE id = ? FOR UPDATE", String.class, paymentId);
    }

    /** The payment's one change: to its final state. A null reason keeps the one it has. */
    private void decide(UUID paymentId, String status, UUID transactionId, LocalDate usageDay, UUID userId, Instant at,
                        String reason) {
        jdbc.update("""
                UPDATE agent_payments SET status = ?, transaction_id = ?, usage_day = ?, decided_at = ?, decided_by = ?,
                    reason = COALESCE(?, reason)
                WHERE id = ?""", status, transactionId, usageDay, Timestamp.from(at), userId, reason, paymentId);
    }

    private MandateRow lockMandate(UUID mandateId, String lock) {
        var rows = jdbc.query("""
                SELECT id, user_id, account_id, currency, per_payment_limit, daily_limit, ask_above_limit, expires_at, revoked_at
                FROM agent_mandates WHERE id = ?""" + " " + lock,
                (rs, n) -> new MandateRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("currency"),
                        rs.getBigDecimal("per_payment_limit"), rs.getBigDecimal("daily_limit"),
                        rs.getBoolean("ask_above_limit"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                mandateId);
        if (rows.isEmpty()) throw new MandateNotFoundException(mandateId);
        return rows.get(0);
    }

    // --- Reading --------------------------------------------------------------------

    private static final String SELECT = """
            SELECT p.id, p.mandate_id, p.reason, a.account_number, p.amount, p.currency, p.intent, p.transaction_id,
                   p.hold_id, p.created_at, p.decided_at, h.expires_at AS hold_expires_at,
                   CASE WHEN p.status = 'PENDING_APPROVAL' AND (h.status <> 'PENDING' OR h.expires_at <= now())
                        THEN 'EXPIRED' ELSE p.status END AS status
            FROM agent_payments p
            JOIN accounts a ON a.id = p.payee_account_id
            LEFT JOIN holds h ON h.id = p.hold_id""";

    @Transactional(readOnly = true)
    public AgentPayment get(UUID paymentId) {
        var found = jdbc.query(SELECT + " WHERE p.id = ?", AgentPaymentService::map, paymentId);
        if (found.isEmpty()) throw new AgentPaymentNotFoundException(paymentId);
        return found.get(0);
    }

    /** The payment, if it was made under {@code mandateId}. */
    @Transactional(readOnly = true)
    public AgentPayment getForMandate(UUID mandateId, UUID paymentId) {
        AgentPayment payment = get(paymentId);
        if (!payment.mandateId().equals(mandateId)) throw new AgentPaymentNotFoundException(paymentId);
        return payment;
    }

    /** The owner may read any payment under their mandates. */
    @Transactional(readOnly = true)
    public AgentPayment getForOwner(UUID userId, UUID paymentId) {
        var found = jdbc.query(SELECT + " JOIN agent_mandates m ON m.id = p.mandate_id WHERE p.id = ? AND m.user_id = ?",
                AgentPaymentService::map, paymentId, userId);
        if (found.isEmpty()) throw new AgentPaymentNotFoundException(paymentId);
        return found.get(0);
    }

    /** Everything an agent attempted under the mandate, newest first: its audit trail. */
    @Transactional(readOnly = true)
    public List<AgentPayment> forMandate(UUID mandateId) {
        return jdbc.query(SELECT + " WHERE p.mandate_id = ? ORDER BY p.created_at DESC, p.id LIMIT 100",
                AgentPaymentService::map, mandateId);
    }

    /** Payments over the limits that await the user's decision. */
    @Transactional(readOnly = true)
    public List<AgentPayment> awaitingApproval(UUID userId) {
        return jdbc.query(SELECT + """
                 JOIN agent_mandates m ON m.id = p.mandate_id
                WHERE m.user_id = ? AND p.status = 'PENDING_APPROVAL' AND h.status = 'PENDING' AND h.expires_at > now()
                ORDER BY p.created_at""", AgentPaymentService::map, userId);
    }

    private Optional<AgentPayment> findByRequestKey(String key) {
        return jdbc.query(SELECT + " WHERE p.request_key = ?", AgentPaymentService::map, key).stream().findFirst();
    }

    private static AgentPayment map(ResultSet rs, int n) throws SQLException {
        String status = rs.getString("status");
        Timestamp decided = rs.getTimestamp("decided_at");
        return new AgentPayment(rs.getObject("id", UUID.class), rs.getObject("mandate_id", UUID.class), status,
                rs.getString("reason"), rs.getString("account_number"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("intent"), rs.getObject("transaction_id", UUID.class), rs.getObject("hold_id", UUID.class),
                "PENDING_APPROVAL".equals(status) ? rs.getTimestamp("hold_expires_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant());
    }

    /** Records the payment's event in the current transaction and returns the payment as stored. */
    private AgentPayment publish(String eventType, UUID paymentId, UUID accountId) {
        AgentPayment payment = get(paymentId);
        outbox.append(eventType, paymentId, payment, accountId);
        return payment;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
