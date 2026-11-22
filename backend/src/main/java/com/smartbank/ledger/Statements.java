package com.smartbank.ledger;

import com.smartbank.account.AccountNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Account statements: the balance at the start of a period (from checkpoints), every
 * posting in the period with a running balance, and the balance at the end. From the
 * customer's side, a credit (money in) is positive and a debit (money out) negative.
 */
@Service
public class Statements {
    static final int MAX_LINES = 1_000;
    static final Duration MAX_RANGE = Duration.ofDays(366);

    private final JdbcTemplate jdbc;
    private final BalanceCheckpoints checkpoints;

    public Statements(JdbcTemplate jdbc, BalanceCheckpoints checkpoints) {
        this.jdbc = jdbc;
        this.checkpoints = checkpoints;
    }

    public record Line(Instant postedAt, UUID transactionId, String type, String counterparty,
                       BigDecimal amount, BigDecimal balance) { }

    /** {@code truncated} is true when the period had more than the maximum number of lines. */
    public record Statement(UUID accountId, String accountNumber, String currency, Instant from, Instant to,
                            BigDecimal openingBalance, BigDecimal moneyIn, BigDecimal moneyOut,
                            BigDecimal closingBalance, List<Line> lines, boolean truncated) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement statement(UUID accountId, Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("'from' must be before 'to'");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0)
            throw new IllegalArgumentException("A statement can cover at most 366 days");
        var account = jdbc.query("SELECT account_number, currency FROM accounts WHERE id = ?",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, accountId);
        if (account.isEmpty()) throw new AccountNotFoundException(accountId);

        BigDecimal opening = checkpoints.customerBalanceAt(accountId, from);
        BigDecimal closing = checkpoints.customerBalanceAt(accountId, to);
        List<Object[]> rows = jdbc.query("""
                SELECT e.created_at, e.transaction_id, t.transaction_type, e.amount,
                       (SELECT a2.account_number FROM ledger_entries e2 JOIN accounts a2 ON a2.id = e2.account_id
                        WHERE e2.transaction_id = e.transaction_id AND e2.account_id <> e.account_id LIMIT 1) AS counterparty
                FROM ledger_entries e JOIN banking_transactions t ON t.id = e.transaction_id
                WHERE e.account_id = ? AND e.created_at >= ? AND e.created_at < ?
                ORDER BY e.created_at, e.id LIMIT ?""",
                (rs, n) -> new Object[] { rs.getTimestamp(1).toInstant(), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5) },
                accountId, Timestamp.from(from), Timestamp.from(to), MAX_LINES + 1);
        boolean truncated = rows.size() > MAX_LINES;
        BigDecimal running = opening, in = BigDecimal.ZERO, out = BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        for (Object[] r : rows.subList(0, Math.min(rows.size(), MAX_LINES))) {
            BigDecimal amount = (BigDecimal) r[3];
            running = running.add(amount);
            if (amount.signum() > 0) in = in.add(amount); else out = out.add(amount.negate());
            lines.add(new Line((Instant) r[0], (UUID) r[1], (String) r[2], (String) r[4], amount, running));
        }
        if (!truncated && running.compareTo(closing) != 0)
            throw new IllegalStateException("Statement does not add up for account " + accountId);
        return new Statement(accountId, account.get(0)[0], account.get(0)[1], from, to, opening, in, out, closing,
                lines, truncated);
    }
}
