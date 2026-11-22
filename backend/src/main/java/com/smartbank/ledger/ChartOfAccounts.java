package com.smartbank.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * The chart of accounts and the reports built on it. Balances are stated on each
 * account's normal side: credits minus debits for credit-normal accounts
 * (liabilities, equity, revenue), debits minus credits for debit-normal ones
 * (assets, expenses). Each report runs in one REPEATABLE READ snapshot.
 */
@Service
public class ChartOfAccounts {
    private final JdbcTemplate jdbc;

    public ChartOfAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountBalance(UUID id, String code, String name, String type, String normalBalance,
                                 String currency, UUID parentId, UUID customerAccountId,
                                 BigDecimal debits, BigDecimal credits, BigDecimal balance) { }

    public record TypeTotal(String type, BigDecimal balance) { }

    /**
     * One currency's trial balance. Balanced when total debits equal total credits;
     * the accounting equation is Assets = Liabilities + Equity + Revenue - Expenses.
     */
    public record TrialBalance(String currency, Instant asOf, List<AccountBalance> accounts,
                               BigDecimal totalDebits, BigDecimal totalCredits, List<TypeTotal> byType,
                               boolean balanced, boolean accountingEquationHolds) { }

    public record JournalLine(String code, String name, String type, BigDecimal debit, BigDecimal credit, String currency) { }

    /**
     * Every account of the currency with its balance from entries dated before
     * {@code asOf} (now if null), each read as its latest checkpoint plus the entries
     * since. Control accounts roll up their sub-accounts.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AccountBalance> accounts(String currency, Instant asOf) {
        Timestamp at = Timestamp.from(asOf == null ? Instant.now() : asOf);
        List<AccountBalance> rows = jdbc.query("""
                SELECT la.id, la.code, la.name, la.account_type, la.normal_balance, la.currency, la.parent_id,
                       la.customer_account_id,
                       COALESCE(cp.debits, 0) + COALESCE(d.debits, 0) AS debits,
                       COALESCE(cp.credits, 0) + COALESCE(d.credits, 0) AS credits
                FROM ledger_accounts la
                LEFT JOIN LATERAL (
                    SELECT c.debits, c.credits, c.as_of FROM ledger_checkpoints c
                    WHERE c.ledger_account_id = la.id AND c.as_of <= ? ORDER BY c.as_of DESC LIMIT 1) cp ON TRUE
                LEFT JOIN LATERAL (
                    SELECT SUM(j.debit) AS debits, SUM(j.credit) AS credits FROM journal_lines j
                    WHERE j.ledger_account_id = la.id AND j.created_at >= COALESCE(cp.as_of, '-infinity')
                      AND j.created_at < ?) d ON TRUE
                WHERE la.currency = ?
                ORDER BY la.code""",
                (rs, n) -> {
                    BigDecimal debits = rs.getBigDecimal("debits"), credits = rs.getBigDecimal("credits");
                    String normal = rs.getString("normal_balance");
                    return new AccountBalance(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("account_type"), normal, rs.getString("currency"),
                            rs.getObject("parent_id", UUID.class), rs.getObject("customer_account_id", UUID.class),
                            debits, credits, normalSide(normal, debits, credits));
                },
                at, at, currency);
        // Control accounts (no postings of their own) show the total of their sub-accounts.
        Map<UUID, BigDecimal[]> rollup = new HashMap<>();
        for (AccountBalance a : rows)
            if (a.parentId() != null)
                rollup.merge(a.parentId(), new BigDecimal[] { a.debits(), a.credits() },
                        (x, y) -> new BigDecimal[] { x[0].add(y[0]), x[1].add(y[1]) });
        List<AccountBalance> result = new ArrayList<>();
        for (AccountBalance a : rows) {
            BigDecimal[] sub = rollup.get(a.id());
            result.add(sub == null ? a : new AccountBalance(a.id(), a.code(), a.name(), a.type(), a.normalBalance(),
                    a.currency(), a.parentId(), a.customerAccountId(), sub[0], sub[1],
                    normalSide(a.normalBalance(), sub[0], sub[1])));
        }
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrialBalance trialBalance(String currency, Instant asOf) {
        List<AccountBalance> all = accounts(currency, asOf);
        // Totals over posting accounts only, so control accounts are not counted twice.
        Set<UUID> parents = new HashSet<>();
        all.forEach(a -> { if (a.parentId() != null) parents.add(a.parentId()); });
        List<AccountBalance> posting = all.stream().filter(a -> !parents.contains(a.id())).toList();
        BigDecimal debits = posting.stream().map(AccountBalance::debits).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = posting.stream().map(AccountBalance::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> byType = new TreeMap<>();
        for (String type : List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE")) byType.put(type, BigDecimal.ZERO);
        posting.forEach(a -> byType.merge(a.type(), a.balance(), BigDecimal::add));
        BigDecimal rightSide = byType.get("LIABILITY").add(byType.get("EQUITY"))
                .add(byType.get("REVENUE")).subtract(byType.get("EXPENSE"));
        return new TrialBalance(currency, asOf, all, debits, credits,
                byType.entrySet().stream().map(e -> new TypeTotal(e.getKey(), e.getValue())).toList(),
                debits.compareTo(credits) == 0, byType.get("ASSET").compareTo(rightSide) == 0);
    }

    /** A transaction's postings as debit and credit lines on its accounts. */
    @Transactional(readOnly = true)
    public List<JournalLine> journal(UUID transactionId) {
        return jdbc.query("""
                SELECT code, name, account_type, debit, credit, currency FROM journal_lines
                WHERE transaction_id = ? ORDER BY debit DESC, code""",
                (rs, n) -> new JournalLine(rs.getString("code"), rs.getString("name"), rs.getString("account_type"),
                        rs.getBigDecimal("debit"), rs.getBigDecimal("credit"), rs.getString("currency")),
                transactionId);
    }

    private static BigDecimal normalSide(String normal, BigDecimal debits, BigDecimal credits) {
        return "DEBIT".equals(normal) ? debits.subtract(credits) : credits.subtract(debits);
    }

}
