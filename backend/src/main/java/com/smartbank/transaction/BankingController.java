package com.smartbank.transaction;

import com.smartbank.auth.AccessService;
import com.smartbank.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

import static com.smartbank.idempotency.IdempotencyService.HEADER;
import static com.smartbank.idempotency.IdempotencyService.fingerprint;

/**
 * Money-moving endpoints take an idempotency key from the Idempotency-Key header
 * (or the requestKey body field). Ownership is checked first, so an unauthorized
 * attempt never consumes a key.
 */
@RestController
@RequestMapping("/api/v1")
public class BankingController {
    private final BankingService service;
    private final AccessService access;
    private final IdempotencyService idempotency;

    public BankingController(BankingService service, AccessService access, IdempotencyService idempotency) {
        this.service = service;
        this.access = access;
        this.idempotency = idempotency;
    }

    @PostMapping("/accounts/{id}/deposits")
    public ResponseEntity<String> deposit(@PathVariable UUID id, @Valid @RequestBody MoneyRequest request,
                                          @RequestHeader(value = HEADER, required = false) String key) {
        access.requireOwner(id);
        return idempotency.execute(key, request.requestKey(), fingerprint("deposit", id, request.amount()),
                HttpStatus.CREATED, op -> service.deposit(id, new MoneyRequest(request.amount(), op)));
    }

    @PostMapping("/accounts/{id}/withdrawals")
    public ResponseEntity<String> withdraw(@PathVariable UUID id, @Valid @RequestBody MoneyRequest request,
                                           @RequestHeader(value = HEADER, required = false) String key) {
        access.requireOwner(id);
        return idempotency.execute(key, request.requestKey(), fingerprint("withdrawal", id, request.amount()),
                HttpStatus.CREATED, op -> service.withdraw(id, new MoneyRequest(request.amount(), op)));
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequest request,
                                           @RequestHeader(value = HEADER, required = false) String key) {
        access.requireOwner(request.fromAccountId());
        return idempotency.execute(key, request.requestKey(),
                fingerprint("transfer", request.fromAccountId(), request.toAccountId(), request.amount()),
                HttpStatus.CREATED, op -> service.transfer(new TransferRequest(
                        request.fromAccountId(), request.toAccountId(), request.amount(), op)));
    }

    @PostMapping("/transfers/by-account-number")
    public ResponseEntity<String> transferByAccountNumber(@Valid @RequestBody TransferByAccountNumberRequest request,
                                                          @RequestHeader(value = HEADER, required = false) String key) {
        access.requireOwner(request.fromAccountId());
        String toAccountNumber = request.toAccountNumber().trim();
        return idempotency.execute(key, request.requestKey(),
                fingerprint("transfer-by-number", request.fromAccountId(), toAccountNumber, request.amount()),
                HttpStatus.CREATED, op -> service.transferByAccountNumber(new TransferByAccountNumberRequest(
                        request.fromAccountId(), toAccountNumber, request.amount(), op)));
    }

    /** All transfers post or none do. The caller must own every source account. */
    @PostMapping("/transfers/linked")
    public ResponseEntity<String> linkedTransfer(@Valid @RequestBody LinkedTransferRequest request,
                                                 @RequestHeader(value = HEADER, required = false) String key) {
        request.transfers().stream().map(LinkedTransferRequest.Leg::fromAccountId).distinct()
                .forEach(access::requireOwner);
        Object[] parts = request.transfers().stream()
                .flatMap(l -> java.util.stream.Stream.of(l.fromAccountId(), l.toAccountId(), l.amount()))
                .toArray();
        return idempotency.execute(key, request.requestKey(), fingerprint("linked-transfer", parts),
                HttpStatus.CREATED, op -> service.linkedTransfer(new LinkedTransferRequest(request.transfers(), op)));
    }

    @GetMapping("/accounts/{id}/transactions")
    public List<TransactionResponse> history(@PathVariable UUID id) {
        access.requireOwner(id);
        return service.history(id);
    }
}
