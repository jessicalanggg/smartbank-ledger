package com.smartbank.transaction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
/**
 * linkId and linkIndex appear only for legs of a linked transfer; reversesTransactionId
 * only for reversals; counterAmount, counterCurrency and fxRate only for FX transfers,
 * where amount and currency are what was sold and the counter fields what was bought.
 */
public record TransactionResponse(UUID id, TransactionType type, UUID fromAccountId,
    UUID toAccountId, BigDecimal amount, String currency, Instant createdAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID linkId,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer linkIndex,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversesTransactionId,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal counterAmount,
    @JsonInclude(JsonInclude.Include.NON_NULL) String counterCurrency,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal fxRate) {
    public static TransactionResponse from(BankingTransaction tx) {
        return new TransactionResponse(tx.getId(), tx.getTransactionType(), tx.getSourceAccountId(),
            tx.getDestinationAccountId(), tx.getAmount(), tx.getCurrency(), tx.getCreatedAt(),
            tx.getLinkId(), tx.getLinkIndex(), tx.getReversesTransactionId(),
            tx.getCounterAmount(), tx.getCounterCurrency(), tx.getFxRate());
    }
}
