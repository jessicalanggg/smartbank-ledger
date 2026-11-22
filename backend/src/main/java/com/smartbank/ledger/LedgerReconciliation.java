package com.smartbank.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/**
 * Checks the books: the ledger as a whole balances, every transaction balances,
 * every cached account balance equals the sum of that account's entries, pending
 * amounts equal the open holds, every posted hold matches its transfer, and the
 * outbox holds exactly one event per posted transaction and per hold state, debits
 * equal credits within each currency, and every entry lands on exactly one account
 * in the chart of accounts, the hash chain of seals is intact, and every balance
 * checkpoint matches the entries before it.
 * Runs in one REPEATABLE READ snapshot so concurrent postings cannot make a
 * consistent ledger look inconsistent.
 */
@Service
public class LedgerReconciliation {
    private final JdbcTemplate jdbc;
    private final LedgerSealer sealer;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerReconciliation(JdbcTemplate jdbc, LedgerSealer sealer) {
        this.jdbc = jdbc;
        this.sealer = sealer;
    }

    /** Outside Spring (for example in migration tests): verification needs only the database. */
    public LedgerReconciliation(JdbcTemplate jdbc) {
        this(jdbc, new LedgerSealer(jdbc, null, java.time.Clock.systemUTC()));
    }

    public record Report(BigDecimal ledgerTotal, int unbalancedTransactions,
                         int transactionsWithoutEntries, int accountsOutOfBalance,
                         int accountsWithWrongPendingAmounts, int postedHoldsNotMatchingTransfer,
                         int transactionsWithoutExactlyOneEvent, int holdsWithWrongEvents,
                         int currenciesOutOfBalance, int entriesWithoutChartAccount, int customerAccountsWithoutChartAccount,
                         int brokenSeals, int inconsistentCheckpoints) {
        public boolean isClean() {
            return ledgerTotal.signum() == 0 && unbalancedTransactions == 0
                    && transactionsWithoutEntries == 0 && accountsOutOfBalance == 0
                    && accountsWithWrongPendingAmounts == 0 && postedHoldsNotMatchingTransfer == 0
                    && transactionsWithoutExactlyOneEvent == 0 && holdsWithWrongEvents == 0
                    && currenciesOutOfBalance == 0 && entriesWithoutChartAccount == 0
                    && customerAccountsWithoutChartAccount == 0 && brokenSeals == 0 && inconsistentCheckpoints == 0;
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", BigDecimal.class);
        Integer unbalanced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
                    HAVING SUM(amount) <> 0 OR COUNT(*) < 2 OR COUNT(DISTINCT currency) <> 1
                ) t""", Integer.class);
        Integer withoutEntries = jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking_transactions t
                WHERE NOT EXISTS (SELECT 1 FROM ledger_entries l WHERE l.transaction_id = t.id)""",
                Integer.class);
        Integer outOfBalance = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM ledger_entries
                           WHERE ledger_account = 'CUSTOMER' GROUP BY account_id) l
                       ON l.account_id = a.id
                WHERE a.balance <> COALESCE(l.total, 0)""", Integer.class);
        // Pending amounts on each account must equal the sum of its open holds.
        Integer wrongPending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a
                WHERE a.pending_debits <> COALESCE((SELECT SUM(amount) FROM holds h
                          WHERE h.status = 'PENDING' AND h.source_account_id = a.id), 0)
                   OR a.pending_credits <> COALESCE((SELECT SUM(amount) FROM holds h
                          WHERE h.status = 'PENDING' AND h.destination_account_id = a.id), 0)""",
                Integer.class);
        // Every posted hold produced exactly the transfer it describes.
        Integer badPostings = jdbc.queryForObject("""
                SELECT COUNT(*) FROM holds h
                LEFT JOIN banking_transactions t ON t.id = h.posted_transaction_id
                WHERE h.status = 'POSTED'
                  AND (t.id IS NULL OR t.transaction_type <> 'TRANSFER' OR t.amount <> h.posted_amount
                       OR t.source_account_id <> h.source_account_id
                       OR t.destination_account_id <> h.destination_account_id)""", Integer.class);
        // The outbox: every posted transaction has exactly one transaction.posted event.
        Integer missingEvents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking_transactions t
                WHERE t.transaction_type <> 'OPENING_BALANCE'
                  AND (SELECT COUNT(*) FROM outbox_events o
                       WHERE o.event_type = 'transaction.posted' AND o.aggregate_id = t.id) <> 1""", Integer.class);
        // Every hold has one hold.created event, and one event for its final state if it has one.
        Integer holdEvents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM holds h
                WHERE (SELECT COUNT(*) FROM outbox_events o WHERE o.aggregate_id = h.id AND o.event_type = 'hold.created') <> 1
                   OR (SELECT COUNT(*) FROM outbox_events o WHERE o.aggregate_id = h.id AND o.event_type <> 'hold.created')
                      <> CASE WHEN h.status = 'PENDING' THEN 0 ELSE 1 END
                   OR (h.status <> 'PENDING' AND NOT EXISTS (SELECT 1 FROM outbox_events o
                       WHERE o.aggregate_id = h.id AND o.event_type = 'hold.' || lower(h.status)))""", Integer.class);
        // Debits equal credits within every currency, not just overall.
        Integer currencies = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT currency FROM ledger_entries GROUP BY currency HAVING SUM(amount) <> 0) c""",
                Integer.class);
        // Every entry lands on exactly one account in the chart of accounts.
        Integer unmapped = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries e
                WHERE (SELECT COUNT(*) FROM journal_lines j WHERE j.entry_id = e.id) <> 1""", Integer.class);
        Integer customersWithoutChart = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts a
                WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.customer_account_id = a.id)""", Integer.class);
        // The hash chain over sealed transactions is intact.
        int brokenSeals = sealer.verify().intact() ? 0 : 1;
        // Every balance checkpoint equals the previous one plus the entries between them,
        // so a checkpoint that missed a late entry, or was altered, is reported.
        Integer checkpoints = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT c.ledger_account_id, c.as_of, c.debits, c.credits,
                           LAG(c.debits) OVER w AS prev_debits, LAG(c.credits) OVER w AS prev_credits,
                           LAG(c.as_of) OVER w AS prev_as_of
                    FROM ledger_checkpoints c
                    WINDOW w AS (PARTITION BY c.ledger_account_id ORDER BY c.as_of)) x
                LEFT JOIN LATERAL (
                    SELECT COALESCE(SUM(j.debit), 0) AS debits, COALESCE(SUM(j.credit), 0) AS credits
                    FROM journal_lines j
                    WHERE j.ledger_account_id = x.ledger_account_id
                      AND j.created_at >= COALESCE(x.prev_as_of, '-infinity') AND j.created_at < x.as_of) d ON TRUE
                WHERE COALESCE(x.prev_debits, 0) + d.debits <> x.debits
                   OR COALESCE(x.prev_credits, 0) + d.credits <> x.credits""", Integer.class);
        return new Report(total, unbalanced, withoutEntries, outOfBalance, wrongPending, badPostings,
                missingEvents, holdEvents, currencies, unmapped, customersWithoutChart, brokenSeals, checkpoints);
    }
}
