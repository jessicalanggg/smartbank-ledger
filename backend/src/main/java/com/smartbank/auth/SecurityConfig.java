package com.smartbank.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.smartbank.ratelimit.RateLimits.class)
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean JwtDecoder jwtDecoder(@Value("${smartbank.jwt-secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }
    @Bean JwtEncoder jwtEncoder(@Value("${smartbank.jwt-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key(secret)));
    }
    private SecretKeySpec key(String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<com.smartbank.ratelimit.RateLimiter> limiter,
            org.springframework.beans.factory.ObjectProvider<com.smartbank.ratelimit.RateLimits> limits,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        // Rate limiting runs after the bearer token is validated, so limits follow the user.
        var rateLimiter = limiter.getIfAvailable();
        var rateLimits = limits.getIfAvailable();
        if (rateLimiter != null && rateLimits != null && rateLimits.apiPerUser() != null)
            http.addFilterAfter(new com.smartbank.ratelimit.RateLimitFilter(rateLimiter, rateLimits, mapper),
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        // AI agents authenticate with a mandate's API key, and only on the agent API.
        http.addFilterBefore(new com.smartbank.agent.AgentKeyFilter(jdbc, mapper),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                .requestMatchers("/api/v1/agent/**").hasAuthority(com.smartbank.agent.AgentAuthentication.AUTHORITY)
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {}))
            .build();
    }
}
