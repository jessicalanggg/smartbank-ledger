package com.smartbank.transaction;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record TransferByAccountNumberRequest(
        @NotNull UUID fromAccountId,

        @NotBlank String toAccountNumber,

        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,

        @Size(max = 100) String requestKey) {
}