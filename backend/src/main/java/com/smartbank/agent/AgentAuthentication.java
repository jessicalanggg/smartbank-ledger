package com.smartbank.agent;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** The authentication of a request made with an agent API key. It grants only the agent API. */
public class AgentAuthentication extends AbstractAuthenticationToken {
    public static final String AUTHORITY = "AGENT";
    private final AgentPrincipal principal;

    public AgentAuthentication(AgentPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override public AgentPrincipal getPrincipal() { return principal; }
    @Override public Object getCredentials() { return null; }
}
