package com.smartbank.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "banking_transactions")
public class BankingTransaction {
    @Id private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;
    @Column(name = "source_account_id") private UUID sourceAccountId;
    @Column(name = "destination_account_id") private UUID destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "request_key", unique = true, length = 100) private String requestKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected BankingTransaction() { }
    public BankingTransaction(TransactionType type, UUID source, UUID destination,
                              BigDecimal amount, String currency, String requestKey) {
        this.id = UUID.randomUUID(); this.transactionType = type;
        this.sourceAccountId = source; this.destinationAccountId = destination;
        this.amount = amount; this.currency = currency; this.requestKey = requestKey;
        // PostgreSQL stores microseconds; truncating here keeps the API response equal to what is stored.
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public UUID getId() { return id; }
    public TransactionType getTransactionType() { return transactionType; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getRequestKey() { return requestKey; }
    public Instant getCreatedAt() { return createdAt; }
}
