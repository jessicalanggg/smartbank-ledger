package com.smartbank.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import com.smartbank.ratelimit.RateLimitedException;
import com.smartbank.ratelimit.RateLimiter;
import com.smartbank.ratelimit.RateLimits;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Credentials(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record Registration(@NotBlank @Email String email, @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final RateLimiter limiter;
    private final RateLimits limits;
    /** Checked against for unknown emails, so they take as long as wrong passwords. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtEncoder jwt,
                          RateLimiter limiter, RateLimits limits) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.limiter = limiter;
        this.limits = limits;
        this.dummyHash = encoder.encode("not-a-real-password-" + java.util.UUID.randomUUID());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TokenResponse register(@Valid @RequestBody Registration request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email))
            throw new DuplicateEmailException();
        AppUser user = users.saveAndFlush(new AppUser(email, encoder.encode(request.password())));
        return token(user);
    }

    /**
     * Each attempt takes a token from the email's bucket first, so parallel guesses
     * cannot slip past the limit; a successful login gives it back, so only failures
     * count. Unknown emails still run a BCrypt check, so response time does not
     * reveal whether an account exists.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Credentials request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String bucket = "login-email:" + email;
        if (limits.enabled()) {
            var decision = limiter.tryAcquire(bucket, limits.loginPerEmail());
            if (!decision.allowed()) throw new RateLimitedException(decision.retryAfter());
        }
        var user = users.findByEmail(email);
        boolean valid = encoder.matches(request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !valid)
            throw new BadCredentialsException("Invalid credentials");
        if (limits.enabled()) limiter.release(bucket, limits.loginPerEmail());
        return token(user.get());
    }

    private TokenResponse token(AppUser user) {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smartbank-local")
                .issuedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                .subject(user.getId().toString())
                .claim("role", user.getRole())
                .build();

        // Explicitly specify HS256 for JWT signing
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .build();

        String accessToken = jwt.encode(
                JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new TokenResponse(accessToken, "Bearer", 1800);
    }
}
