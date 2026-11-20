package com.smartbank.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id private UUID id;
    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;
    @Column(name = "owner_name", nullable = false, length = 120)
    private String ownerName;
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "pending_debits", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingDebits = BigDecimal.ZERO;
    @Column(name = "pending_credits", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingCredits = BigDecimal.ZERO;
    @Version private long version;
    @Column(name = "owner_user_id") private UUID ownerUserId;
    public UUID getOwnerUserId() { return ownerUserId; }

    protected Account() { }
    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public String getOwnerName() { return ownerName; }
    public AccountType getAccountType() { return accountType; }
    public BigDecimal getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public BigDecimal getPendingDebits() { return pendingDebits; }
    public BigDecimal getPendingCredits() { return pendingCredits; }
    /** Posted balance minus money reserved by pending holds; what the owner can spend now. */
    public BigDecimal getAvailableBalance() { return balance.subtract(pendingDebits); }

    public void credit(BigDecimal amount) { balance = balance.add(amount); }
    public void debit(BigDecimal amount) {
        if (getAvailableBalance().compareTo(amount) < 0)
            throw new com.smartbank.transaction.BankingException("Insufficient funds");
        balance = balance.subtract(amount);
    }

    /** Reserves money for an outgoing hold. */
    public void reserveDebit(BigDecimal amount) {
        if (getAvailableBalance().compareTo(amount) < 0)
            throw new com.smartbank.transaction.BankingException("Insufficient funds");
        pendingDebits = pendingDebits.add(amount);
    }
    public void releaseDebit(BigDecimal amount) { pendingDebits = requireNonNegative(pendingDebits.subtract(amount)); }
    public void reserveCredit(BigDecimal amount) { pendingCredits = pendingCredits.add(amount); }
    public void releaseCredit(BigDecimal amount) { pendingCredits = requireNonNegative(pendingCredits.subtract(amount)); }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value.signum() < 0) throw new IllegalStateException("Pending amount would become negative");
        return value;
    }
}
