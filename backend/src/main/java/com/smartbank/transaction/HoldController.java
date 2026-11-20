package com.smartbank.transaction;

import com.smartbank.auth.AccessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public HoldController(HoldService holds, AccessService access) {
        this.holds = holds;
        this.access = access;
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(@Valid @RequestBody CreateHoldRequest request) {
        access.requireOwner(request.fromAccountId());
        return holds.create(request);
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
