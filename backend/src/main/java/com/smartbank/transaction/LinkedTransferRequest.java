package com.smartbank.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Up to 20 transfers that post together or not at all. */
public record LinkedTransferRequest(
    @NotEmpty @Size(max = 20) List<@Valid @NotNull Leg> transfers,
    @Size(max = 100) String requestKey
) {
    public record Leg(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount
    ) { }
}
