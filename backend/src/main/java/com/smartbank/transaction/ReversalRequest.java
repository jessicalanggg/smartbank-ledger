package com.smartbank.transaction;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/** amount is optional: without it, whatever has not been reversed yet is reversed. */
public record ReversalRequest(
    @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @Size(max = 100) String requestKey
) { }
