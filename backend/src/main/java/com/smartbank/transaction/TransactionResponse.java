package com.smartbank.transaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
/** linkId and linkIndex appear only for legs of a linked transfer; reversesTransactionId only for reversals. */
public record TransactionResponse(UUID id, TransactionType type, UUID fromAccountId,
    UUID toAccountId, BigDecimal amount, String currency, Instant createdAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID linkId,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer linkIndex,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversesTransactionId) {
    public static TransactionResponse from(BankingTransaction tx) {
        return new TransactionResponse(tx.getId(), tx.getTransactionType(), tx.getSourceAccountId(),
            tx.getDestinationAccountId(), tx.getAmount(), tx.getCurrency(), tx.getCreatedAt(),
            tx.getLinkId(), tx.getLinkIndex(), tx.getReversesTransactionId());
    }
}
