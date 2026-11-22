package com.smartbank.agent;

import java.util.UUID;

public class AgentPaymentNotFoundException extends RuntimeException {
    public AgentPaymentNotFoundException(UUID id) { super("Agent payment not found: " + id); }
}
