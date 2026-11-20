package com.smartbank.transaction;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/** amount is optional; when omitted the full held amount is posted. */
public record PostHoldRequest(
    @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount
) { }
