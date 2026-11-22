package com.smartbank.agent;

import java.util.UUID;

/** An AI agent signed in with a mandate's API key: the mandate, its owner and the account it may pay from. */
public record AgentPrincipal(UUID mandateId, UUID userId, UUID accountId) { }
