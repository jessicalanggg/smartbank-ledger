package com.smartbank.common;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.UUID;

/** Who is making the current request: the authenticated user, or null for the system. */
public final class Initiator {
    private Initiator() { }

    public static UUID current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // An AI agent acts for the customer who gave it its mandate.
        if (auth instanceof com.smartbank.agent.AgentAuthentication agent)
            return agent.getPrincipal().userId();
        if (auth instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
