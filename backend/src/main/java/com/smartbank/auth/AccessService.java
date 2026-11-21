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
    private final UserRepository users;
    public AccessService(AccountRepository accounts, UserRepository users) { this.accounts = accounts; this.users = users; }
    public UUID currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) throw new AccessDeniedException("Authentication required");
        return UUID.fromString(jwt.getSubject());
    }
    public void requireOwner(UUID accountId) {
        if (!accounts.existsByIdAndOwnerUserId(accountId, currentUserId()))
            throw new AccessDeniedException("Account access denied");
    }
    public boolean isAdmin() {
        return users.findById(currentUserId()).map(u -> "ADMIN".equals(u.getRole())).orElse(false);
    }
    public void requireAdmin() {
        if (!isAdmin()) throw new AccessDeniedException("Administrator access required");
    }
    public void requireOwnerOfAny(UUID... accountIds) {
        UUID user = currentUserId();
        for (UUID id : accountIds)
            if (accounts.existsByIdAndOwnerUserId(id, user)) return;
        throw new AccessDeniedException("Account access denied");
    }
}
