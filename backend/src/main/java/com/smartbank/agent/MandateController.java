package com.smartbank.agent;

import com.smartbank.auth.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The customer's side of agent payments: give an agent a mandate on one of their
 * accounts, see everything it did, approve or decline what went over its limits, and
 * revoke it.
 */
@RestController
@RequestMapping("/api/v1")
public class MandateController {
    private final MandateService mandates;
    private final AgentPaymentService payments;
    private final AccessService access;

    public MandateController(MandateService mandates, AgentPaymentService payments, AccessService access) {
        this.mandates = mandates;
        this.payments = payments;
        this.access = access;
    }

    public record CreateMandateRequest(
            @NotNull UUID accountId,
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal perPaymentLimit,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal dailyLimit,
            @Size(max = 50) List<@NotBlank @Size(max = 40) String> allowedPayeeAccountNumbers,
            @Min(1) @Max(365) Integer expiresInDays,
            Boolean askAboveLimit) { }

    /** Creates a mandate on an account the caller owns. The response holds the API key, shown only here. */
    @PostMapping("/mandates")
    @ResponseStatus(HttpStatus.CREATED)
    public MandateService.CreatedMandate create(@Valid @RequestBody CreateMandateRequest request) {
        access.requireOwner(request.accountId());
        return mandates.create(access.currentUserId(), new MandateService.CreateMandate(request.accountId(),
                request.name(), request.perPaymentLimit(), request.dailyLimit(), request.allowedPayeeAccountNumbers(),
                request.expiresInDays(), request.askAboveLimit()));
    }

    @GetMapping("/mandates")
    public List<MandateService.Mandate> list() {
        return mandates.forUser(access.currentUserId());
    }

    @GetMapping("/mandates/{id}")
    public MandateService.Mandate get(@PathVariable UUID id) {
        return mandates.get(access.currentUserId(), id);
    }

    /** Takes effect at once: the key stops working and payments awaiting approval are declined. */
    @PostMapping("/mandates/{id}/revoke")
    public MandateService.Mandate revoke(@PathVariable UUID id) {
        UUID user = access.currentUserId();
        payments.revoke(user, id);
        return mandates.get(user, id);
    }

    /** Every payment the agent attempted under the mandate, including rejected ones. */
    @GetMapping("/mandates/{id}/payments")
    public List<AgentPaymentService.AgentPayment> payments(@PathVariable UUID id) {
        mandates.get(access.currentUserId(), id);
        return payments.forMandate(id);
    }

    @GetMapping("/agent-payments/awaiting-approval")
    public List<AgentPaymentService.AgentPayment> awaitingApproval() {
        return payments.awaitingApproval(access.currentUserId());
    }

    @GetMapping("/agent-payments/{id}")
    public AgentPaymentService.AgentPayment payment(@PathVariable UUID id) {
        return payments.getForOwner(access.currentUserId(), id);
    }

    @PostMapping("/agent-payments/{id}/approve")
    public AgentPaymentService.AgentPayment approve(@PathVariable UUID id) {
        return payments.approve(access.currentUserId(), id);
    }

    @PostMapping("/agent-payments/{id}/decline")
    public AgentPaymentService.AgentPayment decline(@PathVariable UUID id) {
        return payments.decline(access.currentUserId(), id);
    }
}
