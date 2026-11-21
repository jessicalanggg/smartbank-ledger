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
}
