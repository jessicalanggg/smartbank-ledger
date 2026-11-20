package com.smartbank.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One side of a double-entry posting. Amounts are signed: positive increases the
 * ledger account, negative decreases it. The entries of a transaction sum to zero,
 * which the database checks at commit. Entries are never updated or deleted.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id private UUID id;
    @Column(name = "transaction_id", nullable = false, updatable = false) private UUID transactionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_account", nullable = false, length = 30, updatable = false)
    private LedgerAccount ledgerAccount;
    @Column(name = "account_id", updatable = false) private UUID accountId;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false) private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected LedgerEntry() { }

    private LedgerEntry(UUID transactionId, LedgerAccount ledgerAccount, UUID accountId,
                        BigDecimal amount, String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.ledgerAccount = ledgerAccount;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static LedgerEntry customer(UUID transactionId, UUID accountId, BigDecimal amount,
                                       String currency, Instant createdAt) {
        return new LedgerEntry(transactionId, LedgerAccount.CUSTOMER, accountId, amount, currency, createdAt);
    }

    public static LedgerEntry internal(UUID transactionId, LedgerAccount ledgerAccount, BigDecimal amount,
                                       String currency, Instant createdAt) {
        if (ledgerAccount == LedgerAccount.CUSTOMER)
            throw new IllegalArgumentException("Customer entries must name an account");
        return new LedgerEntry(transactionId, ledgerAccount, null, amount, currency, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public LedgerAccount getLedgerAccount() { return ledgerAccount; }
    public UUID getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
