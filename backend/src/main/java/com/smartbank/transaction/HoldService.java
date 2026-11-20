package com.smartbank.transaction;

import com.smartbank.account.Account;
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
    private final Clock clock;

    public HoldService(HoldRepository holds, BankingService banking, Clock clock) {
        this.holds = holds;
        this.banking = banking;
        this.clock = clock;
    }

    @Transactional
    public HoldResponse create(CreateHoldRequest request) {
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
        Instant now = clock.instant();
        Duration ttl = request.expiresInSeconds() == null
                ? DEFAULT_TTL : Duration.ofSeconds(request.expiresInSeconds());
        return HoldResponse.from(holds.saveAndFlush(new Hold(source.getId(), target.getId(), request.amount(),
                source.getCurrency(), request.requestKey(), now, now.plus(ttl))));
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
        Instant now = clock.instant();
        if (hold.isExpiredAt(now)) {
            // Commit the expiry (noRollbackFor) so the reservation is released either way.
            release(hold, accounts);
            hold.markExpired(now);
            holds.flush();
            throw new HoldExpiredException();
        }
        release(hold, accounts);
        accounts.source().debit(amount);
        accounts.target().credit(amount);
        TransactionResponse tx = banking.record(TransactionType.TRANSFER, hold.getSourceAccountId(),
                hold.getDestinationAccountId(), amount, hold.getCurrency(), null);
        hold.markPosted(amount, tx.id(), now);
        holds.flush();
        return HoldResponse.from(hold);
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
        hold.markVoided(clock.instant());
        holds.flush();
        return HoldResponse.from(hold);
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
