package com.smartbank.transaction;
import java.util.UUID;
public class FxQuoteNotFoundException extends RuntimeException {
    public FxQuoteNotFoundException(UUID id) { super("FX quote not found: " + id); }
}
