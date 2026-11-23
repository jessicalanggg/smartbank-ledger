package com.smartbank.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartbank.auth.AccessService;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.TransactionResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One transaction as the ledger records it: its debit and credit lines on the chart
 * of accounts, and the seal that covers it. For the owners of the accounts it moved
 * money between, and administrators.
 */
@RestController
public class TransactionDetailsController {
    private final BankingService banking;
    private final ChartOfAccounts chart;
    private final AccessService access;
    private final JdbcTemplate jdbc;

    public TransactionDetailsController(BankingService banking, ChartOfAccounts chart, AccessService access,
                                        JdbcTemplate jdbc) {
        this.banking = banking;
        this.chart = chart;
        this.access = access;
        this.jdbc = jdbc;
    }

    /** The seal covering a transaction; null until the sealer runs (within seconds). */
    public record SealInfo(long seq, Instant sealedAt, boolean signed,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String keyId) { }

    public record TransactionDetails(TransactionResponse transaction, List<ChartOfAccounts.JournalLine> journal,
                                     SealInfo seal) { }

    @GetMapping("/api/v1/transactions/{id}/details")
    public TransactionDetails details(@PathVariable UUID id) {
        TransactionResponse tx = banking.getTransaction(id);
        if (!access.isAdmin())
            access.requireOwnerOfAny(java.util.stream.Stream.of(tx.fromAccountId(), tx.toAccountId())
                    .filter(Objects::nonNull).toArray(UUID[]::new));
        SealInfo seal = jdbc.query("""
                SELECT s.seq, s.sealed_at, s.key_id FROM ledger_sealed_transactions st
                JOIN ledger_seals s ON s.seq = st.seal_seq WHERE st.transaction_id = ?""",
                (rs, n) -> new SealInfo(rs.getLong("seq"), rs.getTimestamp("sealed_at").toInstant(),
                        rs.getString("key_id") != null, rs.getString("key_id")), id)
                .stream().findFirst().orElse(null);
        return new TransactionDetails(tx, chart.journal(id), seal);
    }
}
