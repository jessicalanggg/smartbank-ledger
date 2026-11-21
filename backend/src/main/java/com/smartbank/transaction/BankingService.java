package com.smartbank.transaction;

import com.smartbank.account.*;
import com.smartbank.ledger.LedgerAccount;
import com.smartbank.ledger.LedgerEntry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class BankingService {
    private final AccountRepository accounts;
    private final BankingTransactionRepository transactions;
    private final TransactionTemplate transactionTemplate;
    private final PostingWriter writer;

    public BankingService(AccountRepository accounts, BankingTransactionRepository transactions,
            TransactionTemplate transactionTemplate, PostingWriter writer) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.transactionTemplate = transactionTemplate;
        this.writer = writer;
    }

    /*
     * How deposits, withdrawals and transfers keep account locks short:
     *
     * 1. Read the accounts without locking them (existence, currency, a fast
     *    insufficient-funds check on a possibly stale balance).
     * 2. Insert the transaction, its ledger entries and its outbox event, in one
     *    statement. Nothing is locked yet except the new rows and KEY SHARE locks on
     *    the referenced accounts, which do not block balance updates by others.
     * 3. Last, update the balances in one batch, in UUID order. The debit only
     *    applies if the available balance covers it; otherwise everything rolls
     *    back. Account row locks are taken here and held only until commit.
     *
     * A transfer is five database round trips: the idempotency lookup, the account
     * read, the write, the balance batch, and the commit (docs/benchmarks.md).
     *
     * Before this design, both accounts were locked first and held for the whole
     * transfer, which capped transfers into one busy account at one per transfer
     * duration (see docs/benchmarks.md).
     *
     * Idempotency: a retry whose key is already committed is replayed up front. A
     * simultaneous duplicate blocks on the UNIQUE request_key index until the first
     * request commits, then fails; it is then replayed from the committed row.
     */

    public TransactionResponse deposit(UUID accountId, MoneyRequest request) {
        return idempotent(request.requestKey(),
                prior -> replay(prior, TransactionType.DEPOSIT, accountId, request.amount()),
                () -> postSingleAccount(TransactionType.DEPOSIT, accountId, request));
    }

    public TransactionResponse withdraw(UUID accountId, MoneyRequest request) {
        return idempotent(request.requestKey(),
                prior -> replay(prior, TransactionType.WITHDRAWAL, accountId, request.amount()),
                () -> postSingleAccount(TransactionType.WITHDRAWAL, accountId, request));
    }

    public TransactionResponse transferByAccountNumber(TransferByAccountNumberRequest request) {
        Account destination = accounts.findByAccountNumber(request.toAccountNumber().trim())
                .orElseThrow(() -> new BankingException("Destination account not found"));
        return transfer(new TransferRequest(request.fromAccountId(), destination.getId(),
                request.amount(), request.requestKey()));
    }

    public TransactionResponse transfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId()))
            throw new BankingException("Source and destination must differ");
        return idempotent(request.requestKey(), prior -> replay(prior, request), () -> postTransfer(request));
    }

    /**
     * Posts every leg or none. Each leg is checked on its own (accounts exist, differ,
     * share a currency); funds are checked on each account's net change across the
     * group, so an account may pass on money it receives in the same group. Balance
     * updates are one batch in UUID order, like single transfers.
     */
    public LinkedTransferResponse linkedTransfer(LinkedTransferRequest request) {
        List<LinkedTransferRequest.Leg> legs = request.transfers();
        if (legs == null || legs.isEmpty() || legs.size() > 20)
            throw new BankingException("A linked transfer needs between 1 and 20 transfers");
        for (int i = 0; i < legs.size(); i++)
            if (legs.get(i).fromAccountId().equals(legs.get(i).toAccountId()))
                throw new BankingException("Transfer " + i + ": source and destination must differ");
        String key = request.requestKey();
        if (key == null || key.isBlank())
            throw new IllegalArgumentException("A request key is required");
        var previous = transactions.findByRequestKey(key);
        if (previous.isPresent())
            return replayLinked(previous.get(), legs);
        try {
            return transactionTemplate.execute(status -> postLinked(legs, key));
        } catch (DataIntegrityViolationException e) {
            var winner = transactions.findByRequestKey(key);
            if (winner.isPresent())
                return replayLinked(winner.get(), legs);
            throw e;
        }
    }

    private LinkedTransferResponse postLinked(List<LinkedTransferRequest.Leg> legs, String key) {
        Set<UUID> ids = new HashSet<>();
        legs.forEach(l -> { ids.add(l.fromAccountId()); ids.add(l.toAccountId()); });
        Map<UUID, Account> found = new HashMap<>();
        accounts.findAllById(ids).forEach(a -> found.put(a.getId(), a));
        // Net change per account, kept in UUID order for the balance batch.
        Map<UUID, BigDecimal> net = new TreeMap<>();
        UUID linkId = UUID.randomUUID();
        List<BankingTransaction> rows = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            Account source = found.get(leg.fromAccountId()), target = found.get(leg.toAccountId());
            if (source == null) throw new AccountNotFoundException(leg.fromAccountId());
            if (target == null) throw new AccountNotFoundException(leg.toAccountId());
            if (!source.getCurrency().equals(target.getCurrency()))
                throw new BankingException("Transfer " + i + ": cross-currency transfers are not supported");
            net.merge(leg.fromAccountId(), leg.amount().negate(), BigDecimal::add);
            net.merge(leg.toAccountId(), leg.amount(), BigDecimal::add);
            rows.add(BankingTransaction.linkedLeg(linkId, i, leg.fromAccountId(), leg.toAccountId(),
                    leg.amount(), source.getCurrency(), key));
        }
        for (var change : net.entrySet())
            if (change.getValue().signum() < 0
                    && found.get(change.getKey()).getAvailableBalance().compareTo(change.getValue().negate()) < 0)
                throw new BankingException("Insufficient funds in account " + change.getKey());

        List<TransactionResponse> posted = writer.postAll(rows);
        List<PostingWriter.BalanceChange> changes = net.entrySet().stream()
                .filter(e -> e.getValue().signum() != 0)
                .map(e -> new PostingWriter.BalanceChange(e.getKey(), e.getValue()))
                .toList();
        boolean[] applied = writer.applyBalanceChanges(changes);
        for (int i = 0; i < changes.size(); i++)
            if (!applied[i])
                throw new BankingException("Insufficient funds in account " + changes.get(i).accountId());
        return new LinkedTransferResponse(linkId, posted);
    }

    private LinkedTransferResponse replayLinked(BankingTransaction prior, List<LinkedTransferRequest.Leg> legs) {
        if (prior.getLinkId() == null)
            throw new BankingException("Request key was already used for a different operation");
        List<BankingTransaction> stored = transactions.findByLinkIdOrderByLinkIndex(prior.getLinkId());
        boolean same = stored.size() == legs.size();
        for (int i = 0; same && i < legs.size(); i++) {
            var leg = legs.get(i);
            var tx = stored.get(i);
            same = leg.fromAccountId().equals(tx.getSourceAccountId())
                    && leg.toAccountId().equals(tx.getDestinationAccountId())
                    && leg.amount().compareTo(tx.getAmount()) == 0;
        }
        if (!same)
            throw new BankingException("Request key was already used for a different operation");
        return new LinkedTransferResponse(prior.getLinkId(), stored.stream().map(TransactionResponse::from).toList());
    }

    private TransactionResponse idempotent(String requestKey,
            Function<BankingTransaction, TransactionResponse> replay,
            Supplier<TransactionResponse> post) {
        if (requestKey == null || requestKey.isBlank())
            throw new IllegalArgumentException("A request key is required");
        var previous = transactions.findByRequestKey(requestKey);
        if (previous.isPresent())
            return replay.apply(previous.get());
        try {
            return transactionTemplate.execute(status -> post.get());
        } catch (DataIntegrityViolationException e) {
            // A simultaneous request with the same key committed first.
            var winner = transactions.findByRequestKey(requestKey);
            if (winner.isPresent())
                return replay.apply(winner.get());
            throw e;
        }
    }

    private TransactionResponse postSingleAccount(TransactionType type, UUID accountId, MoneyRequest request) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        BigDecimal amount = request.amount();
        if (type == TransactionType.DEPOSIT) {
            TransactionResponse tx = record(type, null, accountId, amount, account.getCurrency(), request.requestKey());
            writer.applyBalanceChanges(List.of(new PostingWriter.BalanceChange(accountId, amount)));
            return tx;
        }
        failFastIfUnavailable(account, amount);
        TransactionResponse tx = record(type, accountId, null, amount, account.getCurrency(), request.requestKey());
        if (!writer.applyBalanceChanges(List.of(new PostingWriter.BalanceChange(accountId, amount.negate())))[0])
            throw new BankingException("Insufficient funds");
        return tx;
    }

    private TransactionResponse postTransfer(TransferRequest request) {
        UUID from = request.fromAccountId(), to = request.toAccountId();
        BigDecimal amount = request.amount();
        // Both accounts in one query.
        Map<UUID, Account> found = new HashMap<>();
        accounts.findAllById(List.of(from, to)).forEach(a -> found.put(a.getId(), a));
        Account source = found.get(from), target = found.get(to);
        if (source == null) throw new AccountNotFoundException(from);
        if (target == null) throw new AccountNotFoundException(to);
        if (!source.getCurrency().equals(target.getCurrency()))
            throw new BankingException("Cross-currency transfers are not supported");
        failFastIfUnavailable(source, amount);
        TransactionResponse tx = record(TransactionType.TRANSFER, from, to, amount, source.getCurrency(),
                request.requestKey());
        // Critical section: row locks from here until commit, taken in UUID order, in one round trip.
        var debit = new PostingWriter.BalanceChange(from, amount.negate());
        var credit = new PostingWriter.BalanceChange(to, amount);
        boolean debitFirst = from.compareTo(to) < 0;
        boolean[] applied = writer.applyBalanceChanges(debitFirst ? List.of(debit, credit) : List.of(credit, debit));
        if (!applied[debitFirst ? 0 : 1])
            throw new BankingException("Insufficient funds");
        return tx;
    }

    /** Rejects early on an unlocked read. Not authoritative: the conditional debit is. */
    private static void failFastIfUnavailable(Account account, BigDecimal amount) {
        if (account.getAvailableBalance().compareTo(amount) < 0)
            throw new BankingException("Insufficient funds");
    }

    /** Two accounts locked in UUID order, the order every money-moving path uses. */
    record LockedPair(Account source, Account target) { }

    LockedPair lockPair(UUID sourceId, UUID targetId) {
        boolean sourceFirst = sourceId.compareTo(targetId) < 0;
        Account first = locked(sourceFirst ? sourceId : targetId);
        Account second = locked(sourceFirst ? targetId : sourceId);
        return sourceFirst ? new LockedPair(first, second) : new LockedPair(second, first);
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
                || prior.getLinkId() != null
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

    Account locked(UUID id) {
        return accounts.findLockedById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Writes the transaction, its balanced ledger entries and its outbox event. Runs
     * inside the caller's transaction, so all of them commit with the balances.
     */
    TransactionResponse record(TransactionType type, UUID from, UUID to,
            BigDecimal amount, String currency, String key) {
        return writer.post(new BankingTransaction(type, from, to, amount, currency, key));
    }

    /** The double-entry postings for a transaction; their amounts sum to zero. */
    static List<LedgerEntry> postings(BankingTransaction tx) {
        UUID id = tx.getId();
        BigDecimal amount = tx.getAmount();
        String currency = tx.getCurrency();
        var at = tx.getCreatedAt();
        return switch (tx.getTransactionType()) {
            case DEPOSIT -> List.of(
                    LedgerEntry.internal(id, LedgerAccount.EXTERNAL_CLEARING, amount.negate(), currency, at),
                    LedgerEntry.customer(id, tx.getDestinationAccountId(), amount, currency, at));
            case WITHDRAWAL -> List.of(
                    LedgerEntry.customer(id, tx.getSourceAccountId(), amount.negate(), currency, at),
                    LedgerEntry.internal(id, LedgerAccount.EXTERNAL_CLEARING, amount, currency, at));
            case TRANSFER -> List.of(
                    LedgerEntry.customer(id, tx.getSourceAccountId(), amount.negate(), currency, at),
                    LedgerEntry.customer(id, tx.getDestinationAccountId(), amount, currency, at));
            case OPENING_BALANCE -> List.of(
                    LedgerEntry.internal(id, LedgerAccount.OPENING_EQUITY, amount.negate(), currency, at),
                    LedgerEntry.customer(id, tx.getDestinationAccountId(), amount, currency, at));
        };
    }
}
