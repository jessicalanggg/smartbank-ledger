package com.smartbank.auth;

import com.smartbank.account.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class AccessService {
    private final AccountRepository accounts;
    public AccessService(AccountRepository accounts) { this.accounts = accounts; }
    public UUID currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) throw new AccessDeniedException("Authentication required");
        return UUID.fromString(jwt.getSubject());
    }
    public void requireOwner(UUID accountId) {
        if (!accounts.existsByIdAndOwnerUserId(accountId, currentUserId()))
            throw new AccessDeniedException("Account access denied");
    }
    public void requireOwnerOfAny(UUID... accountIds) {
        UUID user = currentUserId();
        for (UUID id : accountIds)
            if (accounts.existsByIdAndOwnerUserId(id, user)) return;
        throw new AccessDeniedException("Account access denied");
    }
}
