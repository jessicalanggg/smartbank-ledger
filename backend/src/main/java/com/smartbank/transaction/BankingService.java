package com.smartbank.transaction;

import com.smartbank.account.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class BankingService {
    private final AccountRepository accounts;
    private final BankingTransactionRepository transactions;

    public BankingService(AccountRepository accounts, BankingTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional
    public TransactionResponse deposit(UUID accountId, MoneyRequest request) {
        return postSingleAccount(TransactionType.DEPOSIT, accountId, request);
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, MoneyRequest request) {
        return postSingleAccount(TransactionType.WITHDRAWAL, accountId, request);
    }

    /**
     * Deposits and withdrawals follow the same idempotency protocol as transfers:
     * check the key, lock the account, re-check the key while holding the lock
     * (a concurrent identical request commits before we get the lock), then post.
     * Conflicting reuse that is not serialised by the lock is stopped by the
     * UNIQUE constraint on request_key.
     */
    private TransactionResponse postSingleAccount(TransactionType type, UUID accountId, MoneyRequest request) {
        var previous = transactions.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), type, accountId, request.amount());
        Account account = locked(accountId);
        previous = transactions.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), type, accountId, request.amount());
        if (type == TransactionType.DEPOSIT) {
            account.credit(request.amount());
            return record(type, null, accountId, request.amount(), account.getCurrency(), request.requestKey());
        }
        account.debit(request.amount());
        return record(type, accountId, null, request.amount(), account.getCurrency(), request.requestKey());
    }

    @Transactional
    public TransactionResponse transferByAccountNumber(
            TransferByAccountNumberRequest request) {

        String accountNumber = request.toAccountNumber().trim();

        Account destination = accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BankingException("Destination account not found"));

        TransferRequest transferRequest = new TransferRequest(
                request.fromAccountId(),
                destination.getId(),
                request.amount(),
                request.requestKey());

        return transfer(transferRequest);
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId()))
            throw new BankingException("Source and destination must differ");
        // Sequential retries return the original transfer; conflicting reuse is
        // rejected.
        var previous = transactions.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), request);

        // Deterministic lock ordering avoids deadlocks for opposing transfers.
        UUID firstId = request.fromAccountId().compareTo(request.toAccountId()) < 0
                ? request.fromAccountId()
                : request.toAccountId();
        UUID secondId = firstId.equals(request.fromAccountId()) ? request.toAccountId() : request.fromAccountId();
        Account first = locked(firstId);
        Account second = locked(secondId);
        Account source = first.getId().equals(request.fromAccountId()) ? first : second;
        Account target = first.getId().equals(request.toAccountId()) ? first : second;
        if (!source.getCurrency().equals(target.getCurrency()))
            throw new BankingException("Cross-currency transfers are not supported");
        // Recheck while account locks are held. A simultaneous identical request may
        // still
        // race before commit; the database UNIQUE constraint prevents a duplicate
        // posting.
        previous = transactions.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), request);
        source.debit(request.amount());
        target.credit(request.amount());
        return record(TransactionType.TRANSFER, source.getId(), target.getId(),
                request.amount(), source.getCurrency(), request.requestKey());
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> history(UUID accountId) {
        if (!accounts.existsById(accountId))
            throw new AccountNotFoundException(accountId);
        return transactions.findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(
                accountId, accountId, PageRequest.of(0, 100)).stream()
                .map(TransactionResponse::from).toList();
    }

    private TransactionResponse replay(BankingTransaction prior, TransferRequest request) {
        if (prior.getTransactionType() != TransactionType.TRANSFER
                || !request.fromAccountId().equals(prior.getSourceAccountId())
                || !request.toAccountId().equals(prior.getDestinationAccountId())
                || request.amount().compareTo(prior.getAmount()) != 0)
            throw new BankingException("Request key was already used for a different operation");
        return TransactionResponse.from(prior);
    }

    private TransactionResponse replay(BankingTransaction prior, TransactionType type, UUID accountId,
            BigDecimal amount) {
        UUID priorAccount = type == TransactionType.DEPOSIT
                ? prior.getDestinationAccountId()
                : prior.getSourceAccountId();
        if (prior.getTransactionType() != type
                || !accountId.equals(priorAccount)
                || amount.compareTo(prior.getAmount()) != 0)
            throw new BankingException("Request key was already used for a different operation");
        return TransactionResponse.from(prior);
    }

    private Account locked(UUID id) {
        return accounts.findLockedById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    private TransactionResponse record(TransactionType type, UUID from, UUID to,
            BigDecimal amount, String currency, String key) {
        return TransactionResponse.from(transactions.saveAndFlush(
                new BankingTransaction(type, from, to, amount, currency, key)));
    }
}
