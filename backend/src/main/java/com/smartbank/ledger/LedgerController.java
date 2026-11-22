package com.smartbank.ledger;

import com.smartbank.auth.AccessService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The bank's books: administrators only. */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {
    private final ChartOfAccounts chart;
    private final AccessService access;
    private final LedgerSealer sealer;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SealSigner signer;

    public LedgerController(ChartOfAccounts chart, AccessService access, LedgerSealer sealer,
                            org.springframework.jdbc.core.JdbcTemplate jdbc, SealSigner signer) {
        this.signer = signer;
        this.chart = chart;
        this.access = access;
        this.sealer = sealer;
        this.jdbc = jdbc;
    }

    /** The latest seal: record its hash somewhere outside this database to detect a rewritten chain. */
    @GetMapping("/seals/head")
    public LedgerSealer.Seal sealHead() {
        access.requireAdmin();
        return sealer.head();
    }

    /** The public keys that verify seal signatures. Publish them, so others can check the chain. */
    @GetMapping("/seals/public-keys")
    public java.util.List<SealSigner.PublishedKey> sealPublicKeys() {
        access.requireAdmin();
        return signer.publicKeys();
    }

    /** Recomputes the whole chain and reports the first broken seal, if any. */
    @GetMapping("/seals/verify")
    public LedgerSealer.Verification verifySeals() {
        access.requireAdmin();
        return sealer.verify();
    }

    public record TransactionAudit(UUID id, String type, UUID initiatedBy, String initiatedByEmail,
                                   java.time.Instant createdAt, Long sealSeq) { }

    /** Who started a transaction, and which seal covers it. */
    @GetMapping("/transactions/{id}")
    public TransactionAudit audit(@PathVariable UUID id) {
        access.requireAdmin();
        return jdbc.query("""
                SELECT t.id, t.transaction_type, t.initiated_by, u.email, t.created_at, s.seal_seq
                FROM banking_transactions t
                LEFT JOIN app_users u ON u.id = t.initiated_by
                LEFT JOIN ledger_sealed_transactions s ON s.transaction_id = t.id
                WHERE t.id = ?""",
                (rs, n) -> new TransactionAudit(rs.getObject("id", UUID.class), rs.getString("transaction_type"),
                        rs.getObject("initiated_by", UUID.class), rs.getString("email"),
                        rs.getTimestamp("created_at").toInstant(), (Long) rs.getObject("seal_seq")), id)
                .stream().findFirst().orElseThrow(() -> new com.smartbank.transaction.TransactionNotFoundException(id));
    }

    @GetMapping("/accounts")
    public List<ChartOfAccounts.AccountBalance> accounts(@RequestParam(defaultValue = "CAD") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        access.requireAdmin();
        return chart.accounts(currency, asOf);
    }

    /** Optional {@code asOf}: the books as they stood at that moment. */
    @GetMapping("/trial-balance")
    public ChartOfAccounts.TrialBalance trialBalance(@RequestParam(defaultValue = "CAD") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        access.requireAdmin();
        return chart.trialBalance(currency, asOf);
    }

    @GetMapping("/transactions/{id}/journal")
    public List<ChartOfAccounts.JournalLine> journal(@PathVariable UUID id) {
        access.requireAdmin();
        return chart.journal(id);
    }
}
