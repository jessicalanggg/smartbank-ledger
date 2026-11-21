package com.smartbank.transaction;

import com.smartbank.account.Account;
import com.smartbank.events.EventTypes;
import com.smartbank.events.OutboxWriter;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Two-phase transfers. Lock order everywhere is: hold row first, then the two
 * accounts in UUID order (the same order immediate transfers use), so holds,
 * transfers and the expiry job cannot deadlock with each other.
 *
 * Posting and voiding are idempotent through the hold's state machine: repeating
 * a post with the same amount, or a void, returns the hold as it already is.
 */
@Service
public class HoldService {
    static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final HoldRepository holds;
    private final BankingService banking;
    private final OutboxWriter outbox;
    private final Clock clock;

    public HoldService(HoldRepository holds, BankingService banking, OutboxWriter outbox, Clock clock) {
        this.holds = holds;
        this.banking = banking;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Records an event for a state change of the hold, in the current transaction. */
    private HoldResponse publish(String eventType, Hold hold) {
        HoldResponse response = HoldResponse.from(hold);
        outbox.append(eventType, hold.getId(), response, hold.getSourceAccountId(), hold.getDestinationAccountId());
        return response;
    }

    @Transactional
    public HoldResponse create(CreateHoldRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank())
            throw new IllegalArgumentException("A request key is required");
        if (request.fromAccountId().equals(request.toAccountId()))
            throw new BankingException("Source and destination must differ");
        var previous = holds.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), request);
        var accounts = banking.lockPair(request.fromAccountId(), request.toAccountId());
        // Re-check under the account locks: a simultaneous identical request has committed by now.
        previous = holds.findByRequestKey(request.requestKey());
        if (previous.isPresent())
            return replay(previous.get(), request);
        Account source = accounts.source(), target = accounts.target();
        if (!source.getCurrency().equals(target.getCurrency()))
            throw new BankingException("Cross-currency holds are not supported");
        source.reserveDebit(request.amount());
        target.reserveCredit(request.amount());
        Instant now = now();
        Duration ttl = request.expiresInSeconds() == null
                ? DEFAULT_TTL : Duration.ofSeconds(request.expiresInSeconds());
        return publish(EventTypes.HOLD_CREATED, holds.saveAndFlush(new Hold(source.getId(), target.getId(),
                request.amount(), source.getCurrency(), request.requestKey(), now, now.plus(ttl))));
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
        var accounts = banking.lockPair(hold.getSourceAccountId(), hold.getDestinationAccountId());
        Instant now = now();
        if (hold.isExpiredAt(now)) {
            // Commit the expiry (noRollbackFor) so the reservation is released either way.
            release(hold, accounts);
            hold.markExpired(now);
            holds.flush();
            publish(EventTypes.HOLD_EXPIRED, hold);
            throw new HoldExpiredException();
        }
        release(hold, accounts);
        accounts.source().debit(amount);
        accounts.target().credit(amount);
        TransactionResponse tx = banking.record(TransactionType.TRANSFER, hold.getSourceAccountId(),
                hold.getDestinationAccountId(), amount, hold.getCurrency(), null);
        hold.markPosted(amount, tx.id(), now);
        holds.flush();
        return publish(EventTypes.HOLD_POSTED, hold);
    }

    @Transactional
    public HoldResponse voidHold(UUID holdId) {
        Hold hold = lockedHold(holdId);
        switch (hold.getStatus()) {
            case VOIDED, EXPIRED -> { return HoldResponse.from(hold); }
            case POSTED -> throw new BankingException("Hold was already posted");
            case PENDING -> { }
        }
        var accounts = banking.lockPair(hold.getSourceAccountId(), hold.getDestinationAccountId());
        release(hold, accounts);
        hold.markVoided(now());
        holds.flush();
        return publish(EventTypes.HOLD_VOIDED, hold);
    }

    /** Expires one hold if it is still pending and due. Each call is its own transaction. */
    @Transactional
    public boolean expire(UUID holdId, Instant now) {
        Hold hold = lockedHold(holdId);
        if (hold.getStatus() != HoldStatus.PENDING || !hold.isExpiredAt(now)) return false;
        var accounts = banking.lockPair(hold.getSourceAccountId(), hold.getDestinationAccountId());
        release(hold, accounts);
        hold.markExpired(now);
        holds.flush();
        publish(EventTypes.HOLD_EXPIRED, hold);
        return true;
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

    private static void release(Hold hold, BankingService.LockedPair accounts) {
        accounts.source().releaseDebit(hold.getAmount());
        accounts.target().releaseCredit(hold.getAmount());
    }

    private static HoldResponse replay(Hold prior, CreateHoldRequest request) {
        if (!prior.getSourceAccountId().equals(request.fromAccountId())
                || !prior.getDestinationAccountId().equals(request.toAccountId())
                || prior.getAmount().compareTo(request.amount()) != 0)
            throw new BankingException("Request key was already used for a different hold");
        return HoldResponse.from(prior);
    }
}
