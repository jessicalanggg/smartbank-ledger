package com.smartbank.agent;

import java.util.UUID;

public class MandateNotFoundException extends RuntimeException {
    public MandateNotFoundException(UUID id) { super("Mandate not found: " + id); }
}
