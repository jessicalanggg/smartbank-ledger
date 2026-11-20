package com.smartbank.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A two-phase transfer: money reserved from the source for the destination,
 * later posted (in full or in part), voided, or expired.
 */
@Entity
@Table(name = "holds")
public class Hold {
    @Id private UUID id;
    @Column(name = "source_account_id", nullable = false, updatable = false) private UUID sourceAccountId;
    @Column(name = "destination_account_id", nullable = false, updatable = false) private UUID destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false) private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10) private HoldStatus status;
    @Column(name = "posted_amount", precision = 19, scale = 2) private BigDecimal postedAmount;
    @Column(name = "posted_transaction_id") private UUID postedTransactionId;
    @Column(name = "request_key", nullable = false, length = 100, updatable = false) private String requestKey;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "resolved_at") private Instant resolvedAt;

    protected Hold() { }

    public Hold(UUID source, UUID destination, BigDecimal amount, String currency, String requestKey,
                Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.sourceAccountId = source;
        this.destinationAccountId = destination;
        this.amount = amount;
        this.currency = currency;
        this.status = HoldStatus.PENDING;
        this.requestKey = requestKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void markPosted(BigDecimal postedAmount, UUID transactionId, Instant at) {
        requirePending();
        this.status = HoldStatus.POSTED;
        this.postedAmount = postedAmount;
        this.postedTransactionId = transactionId;
        this.resolvedAt = at;
    }

    void markVoided(Instant at) { requirePending(); status = HoldStatus.VOIDED; resolvedAt = at; }
    void markExpired(Instant at) { requirePending(); status = HoldStatus.EXPIRED; resolvedAt = at; }

    boolean isExpiredAt(Instant now) { return !now.isBefore(expiresAt); }

    private void requirePending() {
        if (status != HoldStatus.PENDING) throw new IllegalStateException("Hold " + id + " is already " + status);
    }

    public UUID getId() { return id; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public BigDecimal getPostedAmount() { return postedAmount; }
    public UUID getPostedTransactionId() { return postedTransactionId; }
    public String getRequestKey() { return requestKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
