package com.smartbank.transaction;

import com.smartbank.auth.AccessService;
import com.smartbank.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/**
 * The payer creates a hold from an account they own. The payee (owner of the
 * destination account) posts or voids it, the way a merchant captures or releases
 * a card authorization. Either party can view it.
 */
@RestController
@RequestMapping("/api/v1")
public class HoldController {
    private final HoldService holds;
    private final AccessService access;
    private final IdempotencyService idempotency;

    public HoldController(HoldService holds, AccessService access, IdempotencyService idempotency) {
        this.holds = holds;
        this.access = access;
        this.idempotency = idempotency;
    }

    /** Idempotent by the Idempotency-Key header or requestKey field, scoped to the caller. */
    @PostMapping("/holds")
    public ResponseEntity<String> create(@Valid @RequestBody CreateHoldRequest request,
                                         @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        access.requireOwner(request.fromAccountId());
        long ttl = request.expiresInSeconds() == null ? HoldService.DEFAULT_TTL.toSeconds() : request.expiresInSeconds();
        return idempotency.execute(key, request.requestKey(),
                IdempotencyService.fingerprint("hold", request.fromAccountId(), request.toAccountId(), request.amount(), ttl),
                HttpStatus.CREATED, op -> holds.create(new CreateHoldRequest(
                        request.fromAccountId(), request.toAccountId(), request.amount(), op, request.expiresInSeconds())));
    }

    @GetMapping("/holds/{id}")
    public HoldResponse get(@PathVariable UUID id) {
        HoldResponse hold = holds.get(id);
        access.requireOwnerOfAny(hold.fromAccountId(), hold.toAccountId());
        return hold;
    }

    @PostMapping("/holds/{id}/post")
    public HoldResponse post(@PathVariable UUID id, @Valid @RequestBody(required = false) PostHoldRequest request) {
        access.requireOwner(holds.get(id).toAccountId());
        return holds.post(id, request == null ? null : request.amount());
    }

    @PostMapping("/holds/{id}/void")
    public HoldResponse voidHold(@PathVariable UUID id) {
        access.requireOwner(holds.get(id).toAccountId());
        return holds.voidHold(id);
    }

    @GetMapping("/accounts/{id}/holds")
    public List<HoldResponse> forAccount(@PathVariable UUID id) {
        access.requireOwner(id);
        return holds.forAccount(id);
    }
}
