package com.smartbank.agent;

import com.smartbank.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The API an AI agent uses, signed in with {@code Authorization: Agent <key>}. It can
 * read its own mandate and payments, and pay from the mandate's account.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final MandateService mandates;
    private final AgentPaymentService payments;
    private final IdempotencyService idempotency;

    public AgentController(MandateService mandates, AgentPaymentService payments, IdempotencyService idempotency) {
        this.mandates = mandates;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    public record PayRequest(@NotBlank @Size(max = 40) String toAccountNumber,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                             @NotBlank @Size(max = 500) String intent) { }

    /** The agent's limits and what is left of today's. */
    @GetMapping("/mandate")
    public MandateService.Mandate mandate(@AuthenticationPrincipal AgentPrincipal agent) {
        return mandates.find(agent.mandateId());
    }

    /**
     * Pays within the mandate. 201 with status POSTED, PENDING_APPROVAL or REJECTED.
     * Idempotent by the Idempotency-Key header, which is required.
     */
    @PostMapping("/payments")
    public ResponseEntity<String> pay(@AuthenticationPrincipal AgentPrincipal agent, @Valid @RequestBody PayRequest request,
                                      @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        return idempotency.executeAs(agent.userId(), key, null,
                IdempotencyService.fingerprint("agent-payment", agent.mandateId(), request.toAccountNumber().trim(),
                        request.amount(), request.intent().trim()),
                HttpStatus.CREATED, op -> payments.pay(agent,
                        new AgentPaymentService.PaymentRequest(request.toAccountNumber(), request.amount(), request.intent()), op));
    }

    @GetMapping("/payments")
    public List<AgentPaymentService.AgentPayment> list(@AuthenticationPrincipal AgentPrincipal agent) {
        return payments.forMandate(agent.mandateId());
    }

    /** One payment, for example to see whether its owner approved it. */
    @GetMapping("/payments/{id}")
    public AgentPaymentService.AgentPayment get(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id) {
        return payments.getForMandate(agent.mandateId(), id);
    }
}
