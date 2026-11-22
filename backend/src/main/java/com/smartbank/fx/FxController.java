package com.smartbank.fx;

import com.smartbank.auth.AccessService;
import com.smartbank.idempotency.IdempotencyService;
import com.smartbank.transaction.BankingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Foreign exchange: get a quote (it locks the rate briefly), then execute it. Any
 * signed-in user can read rates; only administrators set them.
 */
@RestController
@RequestMapping("/api/v1/fx")
public class FxController {
    private final FxService fx;
    private final BankingService banking;
    private final AccessService access;
    private final IdempotencyService idempotency;

    public FxController(FxService fx, BankingService banking, AccessService access, IdempotencyService idempotency) {
        this.fx = fx;
        this.banking = banking;
        this.access = access;
        this.idempotency = idempotency;
    }

    public record SetRate(@NotBlank String baseCurrency, @NotBlank String quoteCurrency,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rate) { }

    public record QuoteRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
                               @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) { }

    public record ExecuteRequest(@NotNull UUID quoteId, @Size(max = 100) String requestKey) { }

    @GetMapping("/rates")
    public List<FxService.Rate> rates() {
        return fx.currentRates();
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public FxService.Rate setRate(@Valid @RequestBody SetRate request) {
        access.requireAdmin();
        return fx.setRate(request.baseCurrency(), request.quoteCurrency(), request.rate(), access.currentUserId());
    }

    /** The payee may be any account; the caller must own the account money is sold from. */
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public FxService.Quote quote(@Valid @RequestBody QuoteRequest request) {
        access.requireOwner(request.fromAccountId());
        return fx.quote(access.currentUserId(), request.fromAccountId(), request.toAccountId(), request.amount());
    }

    /** Executes a quote the caller obtained. Idempotent by key; a quote can be used once. */
    @PostMapping("/transfers")
    public ResponseEntity<String> execute(@Valid @RequestBody ExecuteRequest request,
                                          @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        UUID user = access.currentUserId();
        return idempotency.execute(key, request.requestKey(), IdempotencyService.fingerprint("fx-transfer", request.quoteId()),
                HttpStatus.CREATED, op -> banking.fxTransfer(request.quoteId(), user, op));
    }
}
