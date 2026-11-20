package com.smartbank.transaction;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
/** expiresInSeconds defaults to 7 days; at most 30 days. */
public record CreateHoldRequest(
    @NotNull UUID fromAccountId,
    @NotNull UUID toAccountId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotBlank @Size(max = 100) String requestKey,
    @Min(1) @Max(2_592_000) Long expiresInSeconds
) { }
