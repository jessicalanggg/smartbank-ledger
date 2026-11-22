package com.smartbank.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Balance checkpoints: each ledger account's cumulative debits and credits up to a
 * period boundary, so any balance at any moment is the latest checkpoint before it
 * plus the entries since.
 *
 * A boundary is checkpointed only once it is older than the horizon. Transactions
 * are limited to transaction_timeout, which is far shorter, so by then every entry
 * dated before the boundary has committed or never will. Reconciliation re-checks
 * each checkpoint against the entries, so a late entry would be reported, not lost.
 */
@Component
public class BalanceCheckpoints {
    private static final Logger log = LoggerFactory.getLogger(BalanceCheckpoints.class);
    private static final long ADVISORY_LOCK = 0x434b5054L;  // "CKPT"

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration period;
    private final Duration horizon;

    public BalanceCheckpoints(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                              @Value("${smartbank.ledger.checkpoint-period:P1D}") Duration period,
                              @Value("${smartbank.ledger.checkpoint-horizon:PT10M}") Duration horizon) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.period = period;
        this.horizon = horizon;
    }

    public record Totals(BigDecimal debits, BigDecimal credits) {
        public BigDecimal creditBalance() { return credits.subtract(debits); }
    }

    @Scheduled(fixedDelayString = "${smartbank.ledger.checkpoint-interval:PT15M}")
    public void run() {
        try {
            checkpointDue(clock.instant());
        } catch (RuntimeException e) {
            log.warn("Balance checkpointing failed", e);
        }
    }

    /** Checkpoints every period boundary that is now older than the horizon; returns them. */
    public List<Instant> checkpointDue(Instant now) {
        long step = period.toMillis();
        Instant latest = Instant.ofEpochMilli(Math.floorDiv(now.minus(horizon).toEpochMilli(), step) * step);
        List<Timestamp> done = jdbc.queryForList("SELECT MAX(as_of) FROM ledger_checkpoint_runs", Timestamp.class);
        Instant next = done.get(0) == null ? latest : done.get(0).toInstant().plusMillis(step);
        List<Instant> created = new ArrayList<>();
        for (Instant boundary = next; !boundary.isAfter(latest); boundary = boundary.plusMillis(step))
            if (checkpointAt(boundary) >= 0) created.add(boundary);
        return created;
    }

    /**
     * Writes a checkpoint at {@code asOf} for every account with entries since its
     * previous checkpoint. Returns the number of accounts, or -1 if that boundary was
     * already done. Package-visible so tests can choose the moment.
     */
    int checkpointAt(Instant asOf) {
        Integer count = transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ADVISORY_LOCK);
            Timestamp t = Timestamp.from(asOf);
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_checkpoint_runs WHERE as_of = ?", Integer.class, t);
            if (exists != null && exists > 0) return -1;
            int accounts = jdbc.update("""
                    INSERT INTO ledger_checkpoints (ledger_account_id, as_of, debits, credits, created_at)
                    SELECT j.ledger_account_id, ?, COALESCE(prev.debits, 0) + SUM(j.debit),
                           COALESCE(prev.credits, 0) + SUM(j.credit), now()
                    FROM journal_lines j
                    LEFT JOIN LATERAL (
                        SELECT c.debits, c.credits, c.as_of FROM ledger_checkpoints c
                        WHERE c.ledger_account_id = j.ledger_account_id AND c.as_of < ?
                        ORDER BY c.as_of DESC LIMIT 1) prev ON TRUE
                    WHERE j.created_at >= COALESCE(prev.as_of, '-infinity') AND j.created_at < ?
                    GROUP BY j.ledger_account_id, prev.debits, prev.credits""", t, t, t);
            jdbc.update("INSERT INTO ledger_checkpoint_runs (as_of, accounts, created_at) VALUES (?, ?, now())", t, accounts);
            return accounts;
        });
        if (count != null && count >= 0) log.info("Balance checkpoint at {} for {} accounts", asOf, count);
        return count == null ? -1 : count;
    }

    /** A ledger account's debit and credit totals over entries dated before {@code at}. */
    public Totals totalsAt(UUID ledgerAccountId, Instant at) {
        Timestamp t = Timestamp.from(at);
        return jdbc.queryForObject("""
                SELECT COALESCE(cp.debits, 0) + COALESCE(d.debits, 0) AS debits,
                       COALESCE(cp.credits, 0) + COALESCE(d.credits, 0) AS credits
                FROM (SELECT 1) one
                LEFT JOIN LATERAL (
                    SELECT c.debits, c.credits, c.as_of FROM ledger_checkpoints c
                    WHERE c.ledger_account_id = ? AND c.as_of <= ? ORDER BY c.as_of DESC LIMIT 1) cp ON TRUE
                LEFT JOIN LATERAL (
                    SELECT SUM(j.debit) AS debits, SUM(j.credit) AS credits FROM journal_lines j
                    WHERE j.ledger_account_id = ? AND j.created_at >= COALESCE(cp.as_of, '-infinity') AND j.created_at < ?) d ON TRUE""",
                (rs, n) -> new Totals(rs.getBigDecimal("debits"), rs.getBigDecimal("credits")),
                ledgerAccountId, t, ledgerAccountId, t);
    }

    /** A customer account's balance (credits minus debits) from entries dated before {@code at}. */
    public BigDecimal customerBalanceAt(UUID accountId, Instant at) {
        UUID ledgerAccount = jdbc.queryForObject(
                "SELECT id FROM ledger_accounts WHERE customer_account_id = ?", UUID.class, accountId);
        return totalsAt(ledgerAccount, at).creditBalance();
    }
}
