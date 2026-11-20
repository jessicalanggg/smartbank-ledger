package com.smartbank.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/**
 * Checks the books: the ledger as a whole balances, every transaction balances,
 * every cached account balance equals the sum of that account's entries, pending
 * amounts equal the open holds, and every posted hold matches its transfer.
 * Runs in one REPEATABLE READ snapshot so concurrent postings cannot make a
 * consistent ledger look inconsistent.
 */
@Service
public class LedgerReconciliation {
    private final JdbcTemplate jdbc;

    public LedgerReconciliation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Report(BigDecimal ledgerTotal, int unbalancedTransactions,
                         int transactionsWithoutEntries, int accountsOutOfBalance,
                         int accountsWithWrongPendingAmounts, int postedHoldsNotMatchingTransfer) {
        public boolean isClean() {
            return ledgerTotal.signum() == 0 && unbalancedTransactions == 0
                    && transactionsWithoutEntries == 0 && accountsOutOfBalance == 0
                    && accountsWithWrongPendingAmounts == 0 && postedHoldsNotMatchingTransfer == 0;
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
        return new Report(total, unbalanced, withoutEntries, outOfBalance, wrongPending, badPostings);
    }
}
