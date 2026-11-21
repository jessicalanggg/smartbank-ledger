package com.smartbank.transaction;

import com.smartbank.account.Account;
import com.smartbank.account.AccountNotFoundException;
import com.smartbank.account.AccountRepository;
import com.smartbank.common.UuidOrder;
import com.smartbank.events.EventTypes;
import com.smartbank.events.OutboxWriter;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-phase transfers, with account locks held only at the very end.
 *
 * - Create: read the accounts without locking, insert the hold and its event, then
 *   reserve the money with one conditional statement per account (the payer's only
 *   applies if its available balance covers the hold).
 * - Post, void, expire: lock the hold row first (it is the hold's own state machine
 *   and only ever contended by requests for the same hold), write the transfer, the
 *   hold's new state and the event, then apply every account change (balance and
 *   pending amounts together) in one batch.
 *
 * Account rows are locked in {@link com.smartbank.common.UuidOrder} by that final
 * batch, as in every other path, and held only until commit.
 *
 * Posting and voiding are idempotent through the hold's state machine: repeating
 * a post with the same amount, or a void, returns the hold as it already is.
 * Creating is idempotent by request key: a simultaneous identical request waits on
 * the unique key and is then replayed.
 */
@Service
public class HoldService {
    static final Duration DEFAULT_TTL = Duration.ofDays(7);
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final HoldRepository holds;
    private final BankingService banking;
    private final AccountRepository accounts;
    private final PostingWriter writer;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public HoldService(HoldRepository holds, BankingService banking, AccountRepository accounts, PostingWriter writer,
                       OutboxWriter outbox, TransactionTemplate transactionTemplate, Clock clock) {
        this.holds = holds;
        this.banking = banking;
        this.accounts = accounts;
        this.writer = writer;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Records an event for a state change of the hold, in the current transaction. */
    private HoldResponse publish(String eventType, Hold hold) {
        HoldResponse response = HoldResponse.from(hold);
        outbox.append(eventType, hold.getId(), response, hold.getSourceAccountId(), hold.getDestinationAccountId());
        return response;
    }

    public HoldResponse create(CreateHoldRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank())
            throw new IllegalArgumentException("A request key is required");
        if (request.fromAccountId().equals(request.toAccountId()))
            throw new BankingException("Source and destination must differ");
        for (int attempt = 1; ; attempt++) {
            var previous = holds.findByRequestKey(request.requestKey());
            if (previous.isPresent())
                return replay(previous.get(), request);
            try {
                return transactionTemplate.execute(status -> insertHold(request));
            } catch (DataIntegrityViolationException e) {
                // A simultaneous request with the same key committed first.
                var winner = holds.findByRequestKey(request.requestKey());
                if (winner.isPresent()) return replay(winner.get(), request);
                throw e;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_DEADLOCK_RETRIES) throw e;
            }
        }
    }

    private HoldResponse insertHold(CreateHoldRequest request) {
        UUID from = request.fromAccountId(), to = request.toAccountId();
        Map<UUID, Account> found = new HashMap<>();
        accounts.findAllById(List.of(from, to)).forEach(a -> found.put(a.getId(), a));
        Account source = found.get(from), target = found.get(to);
        if (source == null) throw new AccountNotFoundException(from);
        if (target == null) throw new AccountNotFoundException(to);
        if (!source.getCurrency().equals(target.getCurrency()))
            throw new BankingException("Cross-currency holds are not supported");
        if (source.getAvailableBalance().compareTo(request.amount()) < 0)
            throw new BankingException("Insufficient funds");  // fast fail on an unlocked read
        Instant now = now();
        Duration ttl = request.expiresInSeconds() == null ? DEFAULT_TTL : Duration.ofSeconds(request.expiresInSeconds());
        Hold hold = holds.saveAndFlush(new Hold(from, to, request.amount(), source.getCurrency(),
                request.requestKey(), now, now.plus(ttl)));
        HoldResponse response = publish(EventTypes.HOLD_CREATED, hold);
        // Critical section: reserve last.
        boolean[] applied = apply(
                new PostingWriter.AccountChange(from, BigDecimal.ZERO, request.amount(), BigDecimal.ZERO),
                new PostingWriter.AccountChange(to, BigDecimal.ZERO, BigDecimal.ZERO, request.amount()));
        if (!applied[0]) throw new BankingException("Insufficient funds");
        return response;
    }

    /** Posts the hold, in full when amount is null, releasing any remainder. */
    @Transactional(noRollbackFor = HoldExpiredException.class)
    public HoldResponse post(UUID holdId, BigDecimal requestedAmount) {
        Hold hold = lockedHold(holdId);
        BigDecimal amount = requestedAmount == null ? hold.getAmount() : requestedAmount;
        switch (hold.getStatus()) {
            case POSTED -> {
                if (hold.getPostedAmount().compareTo(amount) == 0) return HoldResponse.from(hold);
                throw new BankingException("Hold was already posted for a different amount");
            }
            case VOIDED -> throw new BankingException("Hold was voided");
            case EXPIRED -> throw new HoldExpiredException();
            case PENDING -> { }
        }
        if (amount.compareTo(hold.getAmount()) > 0)
            throw new BankingException("Cannot post more than the held amount");
        Instant now = now();
        if (hold.isExpiredAt(now)) {
            // Commit the expiry (noRollbackFor) so the reservation is released either way.
            hold.markExpired(now);
            holds.flush();
            publish(EventTypes.HOLD_EXPIRED, hold);
            release(hold);
            throw new HoldExpiredException();
        }
        TransactionResponse tx = banking.record(TransactionType.TRANSFER, hold.getSourceAccountId(),
                hold.getDestinationAccountId(), amount, hold.getCurrency(), null);
        hold.markPosted(amount, tx.id(), now);
        holds.flush();
        HoldResponse response = publish(EventTypes.HOLD_POSTED, hold);
        // Critical section: release the reservation and move the posted amount, in one batch.
        apply(new PostingWriter.AccountChange(hold.getSourceAccountId(), amount.negate(), hold.getAmount().negate(), BigDecimal.ZERO),
              new PostingWriter.AccountChange(hold.getDestinationAccountId(), amount, BigDecimal.ZERO, hold.getAmount().negate()));
        return response;
    }

    @Transactional
    public HoldResponse voidHold(UUID holdId) {
        Hold hold = lockedHold(holdId);
        switch (hold.getStatus()) {
            case VOIDED, EXPIRED -> { return HoldResponse.from(hold); }
            case POSTED -> throw new BankingException("Hold was already posted");
            case PENDING -> { }
        }
        hold.markVoided(now());
        holds.flush();
        HoldResponse response = publish(EventTypes.HOLD_VOIDED, hold);
        release(hold);
        return response;
    }

    /** Expires one hold if it is still pending and due. Each call is its own transaction. */
    @Transactional
    public boolean expire(UUID holdId, Instant now) {
        Hold hold = lockedHold(holdId);
        if (hold.getStatus() != HoldStatus.PENDING || !hold.isExpiredAt(now)) return false;
        hold.markExpired(now);
        holds.flush();
        publish(EventTypes.HOLD_EXPIRED, hold);
        release(hold);
        return true;
    }

    /** Releases the hold's reservation on both accounts; the last step of the transaction. */
    private void release(Hold hold) {
        apply(new PostingWriter.AccountChange(hold.getSourceAccountId(), BigDecimal.ZERO, hold.getAmount().negate(), BigDecimal.ZERO),
              new PostingWriter.AccountChange(hold.getDestinationAccountId(), BigDecimal.ZERO, BigDecimal.ZERO, hold.getAmount().negate()));
    }

    /**
     * Applies the payer's and payee's changes in the shared lock order, in one round
     * trip. Returns {payer applied, payee applied}.
     */
    private boolean[] apply(PostingWriter.AccountChange payer, PostingWriter.AccountChange payee) {
        boolean payerFirst = UuidOrder.before(payer.accountId(), payee.accountId());
        boolean[] applied = writer.applyAccountChanges(payerFirst ? List.of(payer, payee) : List.of(payee, payer));
        return payerFirst ? applied : new boolean[] { applied[1], applied[0] };
    }

    @Transactional(readOnly = true)
    public List<UUID> dueForExpiry(Instant now, int limit) {
        return holds.findIdsDueForExpiry(now, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public HoldResponse get(UUID holdId) {
        return holds.findById(holdId).map(HoldResponse::from).orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    @Transactional(readOnly = true)
    public List<HoldResponse> forAccount(UUID accountId) {
        return holds.findForAccount(accountId, PageRequest.of(0, 100)).stream().map(HoldResponse::from).toList();
    }

    /** Current time at PostgreSQL's microsecond precision, so responses equal what is stored. */
    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private Hold lockedHold(UUID holdId) {
        return holds.findLockedById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    }


    private static HoldResponse replay(Hold prior, CreateHoldRequest request) {
        if (!prior.getSourceAccountId().equals(request.fromAccountId())
                || !prior.getDestinationAccountId().equals(request.toAccountId())
                || prior.getAmount().compareTo(request.amount()) != 0)
            throw new BankingException("Request key was already used for a different hold");
        return HoldResponse.from(prior);
    }
}
