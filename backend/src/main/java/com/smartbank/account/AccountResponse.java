package com.smartbank.account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * balance is the posted balance. availableBalance subtracts money reserved by
 * pending outgoing holds; pendingCredits is money held for this account by others.
 */
public record AccountResponse(
        UUID id,
        String accountNumber,
        String ownerName,
        AccountType accountType,
        BigDecimal balance,
        BigDecimal availableBalance,
        BigDecimal pendingDebits,
        BigDecimal pendingCredits,
        String currency
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(), account.getAccountNumber(),
                account.getOwnerName(), account.getAccountType(),
                account.getBalance(), account.getAvailableBalance(),
                account.getPendingDebits(), account.getPendingCredits(),
                account.getCurrency());
    }
}
