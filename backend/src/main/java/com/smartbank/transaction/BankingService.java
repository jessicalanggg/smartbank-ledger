package com.smartbank.transaction;

import com.smartbank.account.*;
import com.smartbank.common.UuidOrder;
import com.smartbank.ledger.LedgerAccount;
import com.smartbank.ledger.LedgerEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.ConcurrencyFailureException;
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

    /** Posts a deposit, through the batcher when it is enabled. */
    public TransactionResponse deposit(UUID accountId, MoneyRequest request) {
        requireKey(request.requestKey());
        return batcher != null ? batcher.submit(PostingBatcher.Posting.deposit(accountId, request))
                : depositDirect(accountId, request);
    }

    /** Posts a withdrawal, through the batcher when it is enabled. */
    public TransactionResponse withdraw(UUID accountId, MoneyRequest request) {
        requireKey(request.requestKey());
        return batcher != null ? batcher.submit(PostingBatcher.Posting.withdrawal(accountId, request))
                : withdrawDirect(accountId, request);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A request key is required");
    }

    /** A deposit in its own transaction, without batching. */
    TransactionResponse depositDirect(UUID accountId, MoneyRequest request) {
        return idempotent(request.requestKey(),
                prior -> replay(prior, TransactionType.DEPOSIT, accountId, request.amount()),
                () -> postSingleAccount(TransactionType.DEPOSIT, accountId, request));
    }

    /** A withdrawal in its own transaction, without batching. */
    TransactionResponse withdrawDirect(UUID accountId, MoneyRequest request) {
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

    /** Posts a transfer, through the batcher when it is enabled. */
    public TransactionResponse transfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId()))
            throw new BankingException("Source and destination must differ");
        requireKey(request.requestKey());
        return batcher != null ? batcher.submit(PostingBatcher.Posting.transfer(request)) : transferDirect(request);
    }

    /** Posts a single transfer in its own transaction, without batching. */
    TransactionResponse transferDirect(TransferRequest request) {
        return idempotent(request.requestKey(), prior -> replay(prior, request), () -> postTransfer(request));
    }

    TransactionResponse replayTransfer(BankingTransaction prior, TransferRequest request) {
        return replay(prior, request);
    }

    TransactionResponse replaySingle(BankingTransaction prior, TransactionType type, UUID accountId, BigDecimal amount) {
        return replay(prior, type, accountId, amount);
    }

    private PostingBatcher batcher;

    @Autowired(required = false)
    void setBatcher(@Lazy PostingBatcher batcher,
                    @Value("${smartbank.batching.enabled:true}") boolean enabled) {
        this.batcher = enabled ? batcher : null;
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
        requireKey(request.requestKey());
        return batcher != null ? batcher.submitLinked(request) : linkedTransferDirect(request);
    }

    /** A linked transfer in its own transaction, without batching. Legs are already validated. */
    LinkedTransferResponse linkedTransferDirect(LinkedTransferRequest request) {
        List<LinkedTransferRequest.Leg> legs = request.transfers();
        String key = request.requestKey();
        var previous = transactions.findByRequestKey(key);
        if (previous.isPresent())
            return replayLinked(previous.get(), legs);
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> postLinked(legs, key));
            } catch (DataIntegrityViolationException e) {
                var winner = transactions.findByRequestKey(key);
                if (winner.isPresent())
                    return replayLinked(winner.get(), legs);
                throw e;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_DEADLOCK_RETRIES) throw e;
                var winner = transactions.findByRequestKey(key);
                if (winner.isPresent())
                    return replayLinked(winner.get(), legs);
            }
        }
    }

    private LinkedTransferResponse postLinked(List<LinkedTransferRequest.Leg> legs, String key) {
        Set<UUID> ids = new HashSet<>();
        legs.forEach(l -> { ids.add(l.fromAccountId()); ids.add(l.toAccountId()); });
        Map<UUID, Account> found = new HashMap<>();
        accounts.findAllById(ids).forEach(a -> found.put(a.getId(), a));
        // Net change per account, kept in UUID order for the balance batch.
        Map<UUID, BigDecimal> net = new TreeMap<>(UuidOrder.COMPARATOR);
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

    LinkedTransferResponse replayLinked(BankingTransaction prior, List<LinkedTransferRequest.Leg> legs) {
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

    /**
     * Reverses a transfer, deposit or withdrawal, in full (amount null: whatever is
     * left) or in part. The original is locked first, so reversals of one
     * transaction are decided one at a time against what is left; a database
     * trigger re-checks the total at commit.
     */
    public TransactionResponse reverse(UUID originalId, ReversalRequest request) {
        return idempotent(request.requestKey(),
                prior -> replayReversal(prior, originalId, request.amount()),
                () -> postReversal(originalId, request.amount(), request.requestKey()));
    }

    /** The transaction, for authorization checks before reversing it. */
    public TransactionResponse getTransaction(UUID id) {
        return transactions.findById(id).map(TransactionResponse::from)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    private TransactionResponse postReversal(UUID originalId, BigDecimal requested, String key) {
        BankingTransaction original = transactions.findLockedById(originalId)
                .orElseThrow(() -> new TransactionNotFoundException(originalId));
        // A simultaneous identical request may have committed while we waited for the lock.
        var previous = transactions.findByRequestKey(key);
        if (previous.isPresent()) return replayReversal(previous.get(), originalId, requested);
        if (!Set.of(TransactionType.TRANSFER, TransactionType.DEPOSIT, TransactionType.WITHDRAWAL)
                .contains(original.getTransactionType()))
            throw new BankingException("Only transfers, deposits and withdrawals can be reversed");
        BigDecimal remaining = original.getAmount().subtract(transactions.sumReversedAmount(originalId));
        if (remaining.signum() <= 0)
            throw new BankingException("Transaction is already fully reversed");
        BigDecimal amount = requested == null ? remaining : requested;
        if (amount.compareTo(remaining) > 0)
            throw new BankingException("Cannot reverse more than the remaining " + remaining.toPlainString());

        BankingTransaction reversal = BankingTransaction.reversalOf(original, amount, key);
        UUID from = reversal.getSourceAccountId(), to = reversal.getDestinationAccountId();
        if (from != null)
            failFastIfUnavailable(accounts.findById(from).orElseThrow(() -> new AccountNotFoundException(from)), amount);
        TransactionResponse response = writer.post(reversal);
        List<PostingWriter.BalanceChange> changes = new ArrayList<>();
        if (from != null) changes.add(new PostingWriter.BalanceChange(from, amount.negate()));
        if (to != null) changes.add(new PostingWriter.BalanceChange(to, amount));
        changes.sort((a, b) -> UuidOrder.COMPARATOR.compare(a.accountId(), b.accountId()));
        boolean[] applied = writer.applyBalanceChanges(changes);
        for (int i = 0; i < changes.size(); i++)
            if (!applied[i]) throw new BankingException("Insufficient funds");
        return response;
    }

    private TransactionResponse replayReversal(BankingTransaction prior, UUID originalId, BigDecimal requested) {
        if (prior.getTransactionType() != TransactionType.REVERSAL
                || !originalId.equals(prior.getReversesTransactionId())
                || (requested != null && requested.compareTo(prior.getAmount()) != 0))
            throw new BankingException("Request key was already used for a different operation");
        return TransactionResponse.from(prior);
    }

    private TransactionResponse idempotent(String requestKey,
            Function<BankingTransaction, TransactionResponse> replay,
            Supplier<TransactionResponse> post) {
        if (requestKey == null || requestKey.isBlank())
            throw new IllegalArgumentException("A request key is required");
        for (int attempt = 1; ; attempt++) {
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
            } catch (ConcurrencyFailureException e) {
                // Chosen as a deadlock victim (possible when the same key is in flight in a batch).
                // Nothing was written, and the request key makes a retry safe.
                if (attempt >= MAX_DEADLOCK_RETRIES) throw e;
            }
        }
    }

    private static final int MAX_DEADLOCK_RETRIES = 3;

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
        boolean debitFirst = UuidOrder.before(from, to);
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
            // A reversal follows the original's path backwards; a null side is external clearing.
            case REVERSAL -> List.of(
                    tx.getSourceAccountId() == null
                            ? LedgerEntry.internal(id, LedgerAccount.EXTERNAL_CLEARING, amount.negate(), currency, at)
                            : LedgerEntry.customer(id, tx.getSourceAccountId(), amount.negate(), currency, at),
                    tx.getDestinationAccountId() == null
                            ? LedgerEntry.internal(id, LedgerAccount.EXTERNAL_CLEARING, amount, currency, at)
                            : LedgerEntry.customer(id, tx.getDestinationAccountId(), amount, currency, at));
        };
    }
}
