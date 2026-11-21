package com.smartbank.auth;

import com.smartbank.account.AccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccessService service = new AccessService(accounts, mock(UserRepository.class));
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void authenticate(UUID userId) {
        Jwt jwt = new Jwt("test", Instant.now(), Instant.now().plusSeconds(60),
                java.util.Map.of("alg", "HS256"), java.util.Map.of("sub", userId.toString()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "test", java.util.List.of()));
    }
    @Test void ownerCanAccessOwnAccount() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID();
        authenticate(user);
        when(accounts.existsByIdAndOwnerUserId(account, user)).thenReturn(true);
        assertDoesNotThrow(() -> service.requireOwner(account));
    }
    @Test void anotherCustomerCannotAccessAccount() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID();
        authenticate(user);
        assertThrows(AccessDeniedException.class, () -> service.requireOwner(account));
    }
}
