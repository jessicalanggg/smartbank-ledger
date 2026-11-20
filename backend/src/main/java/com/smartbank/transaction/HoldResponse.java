package com.smartbank.transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record HoldResponse(UUID id, UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency,
    HoldStatus status, BigDecimal postedAmount, UUID postedTransactionId,
    Instant createdAt, Instant expiresAt, Instant resolvedAt) {
    public static HoldResponse from(Hold h) {
        return new HoldResponse(h.getId(), h.getSourceAccountId(), h.getDestinationAccountId(), h.getAmount(),
            h.getCurrency(), h.getStatus(), h.getPostedAmount(), h.getPostedTransactionId(),
            h.getCreatedAt(), h.getExpiresAt(), h.getResolvedAt());
    }
}
