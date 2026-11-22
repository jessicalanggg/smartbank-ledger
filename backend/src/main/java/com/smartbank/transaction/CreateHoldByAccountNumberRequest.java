package com.smartbank.transaction;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
/** Like CreateHoldRequest, but the payee is given by account number, as people know it. */
public record CreateHoldByAccountNumberRequest(
    @NotNull UUID fromAccountId,
    @NotBlank String toAccountNumber,
    @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @Size(max = 100) String requestKey,
    @Min(1) @Max(2_592_000) Long expiresInSeconds
) { }
