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
    /** Set for legs of a linked transfer: the group id and this leg's position. */
    @Column(name = "link_id", updatable = false) private UUID linkId;
    @Column(name = "link_index", updatable = false) private Short linkIndex;
    /** Set for reversals: the transaction this one (partly) undoes. */
    @Column(name = "reverses_transaction_id", updatable = false) private UUID reversesTransactionId;
    /** The user whose request created this transaction; null for the system. */
    @Column(name = "initiated_by", updatable = false) private UUID initiatedBy;
    /** FX transfers only: the buy side, the rate applied and the quote used. */
    @Column(name = "counter_amount", precision = 19, scale = 2, updatable = false) private BigDecimal counterAmount;
    @Column(name = "counter_currency", length = 3, updatable = false) private String counterCurrency;
    @Column(name = "fx_rate", precision = 20, scale = 10, updatable = false) private BigDecimal fxRate;
    @Column(name = "fx_quote_id", updatable = false) private UUID fxQuoteId;
    /** Set when an AI agent caused this transfer: the mandate it paid under. */
    @Column(name = "agent_mandate_id", updatable = false) private UUID agentMandateId;

    protected BankingTransaction() { }
    public BankingTransaction(TransactionType type, UUID source, UUID destination,
                              BigDecimal amount, String currency, String requestKey) {
        this.id = UUID.randomUUID(); this.transactionType = type;
        this.sourceAccountId = source; this.destinationAccountId = destination;
        this.amount = amount; this.currency = currency; this.requestKey = requestKey;
        // PostgreSQL stores microseconds; truncating here keeps the API response equal to what is stored.
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** A leg of a linked transfer. Only leg 0 carries the group's request key. */
    static BankingTransaction linkedLeg(UUID linkId, int index, UUID source, UUID destination,
                                        BigDecimal amount, String currency, String groupRequestKey) {
        BankingTransaction leg = new BankingTransaction(TransactionType.TRANSFER, source, destination, amount,
                currency, index == 0 ? groupRequestKey : null);
        leg.linkId = linkId;
        leg.linkIndex = (short) index;
        return leg;
    }
    public UUID getId() { return id; }
    public TransactionType getTransactionType() { return transactionType; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getRequestKey() { return requestKey; }
    public Instant getCreatedAt() { return createdAt; }
    /**
     * A reversal of {@code original}: the same path in the opposite direction. The
     * original's payee pays back its payer; for a deposit or withdrawal the external
     * side is null, as on the original.
     */
    static BankingTransaction reversalOf(BankingTransaction original, BigDecimal amount, String requestKey) {
        BankingTransaction reversal = new BankingTransaction(TransactionType.REVERSAL,
                original.getDestinationAccountId(), original.getSourceAccountId(), amount, original.getCurrency(), requestKey);
        reversal.reversesTransactionId = original.getId();
        return reversal;
    }

    public UUID getReversesTransactionId() { return reversesTransactionId; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public BigDecimal getCounterAmount() { return counterAmount; }
    public String getCounterCurrency() { return counterCurrency; }
    public BigDecimal getFxRate() { return fxRate; }
    public UUID getFxQuoteId() { return fxQuoteId; }

    /** An FX transfer: sell {@code amount} of {@code currency}, buy {@code counterAmount} of {@code counterCurrency}. */
    static BankingTransaction fxTransfer(UUID from, UUID to, BigDecimal amount, String currency, BigDecimal counterAmount,
                                         String counterCurrency, BigDecimal rate, UUID quoteId, String requestKey) {
        BankingTransaction tx = new BankingTransaction(TransactionType.FX_TRANSFER, from, to, amount, currency, requestKey);
        tx.counterAmount = counterAmount;
        tx.counterCurrency = counterCurrency;
        tx.fxRate = rate;
        tx.fxQuoteId = quoteId;
        return tx;
    }

    /** Records who started the transaction; set before it is written. */
    BankingTransaction initiatedBy(UUID user) {
        this.initiatedBy = user;
        return this;
    }
    /** Records the agent mandate this transfer was paid under; set before it is written. */
    BankingTransaction byAgent(UUID mandateId) {
        this.agentMandateId = mandateId;
        return this;
    }
    public UUID getAgentMandateId() { return agentMandateId; }
    public UUID getLinkId() { return linkId; }
    public Integer getLinkIndex() { return linkIndex == null ? null : linkIndex.intValue(); }
}
