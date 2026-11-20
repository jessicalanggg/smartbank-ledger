package com.smartbank.transaction;
import java.util.UUID;
public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(UUID id) { super("Hold not found: " + id); }
}
