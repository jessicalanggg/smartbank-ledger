package com.smartbank.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Applies per-IP limits to login, registration and unauthenticated requests, and
 * per-user limits to authenticated requests, with a stricter one for writes. Runs
 * after the bearer token is validated, so a user's limit follows the user, not the
 * address. Refused requests get 429 and a Retry-After header.
 *
 * The client IP is the connection's remote address. Behind a reverse proxy, enable
 * Spring's forwarded-header support so this is the real client, not the proxy.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RateLimiter limiter;
    private final RateLimits limits;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, RateLimits limits, ObjectMapper mapper) {
        this.limiter = limiter;
        this.limits = limits;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limits.enabled() || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getRemoteAddr();
        String path = request.getRequestURI();
        RateLimiter.Decision decision;
        if (path.startsWith("/api/v1/auth/")) {
            decision = limiter.tryAcquire("auth-ip:" + ip, limits.authPerIp());
            if (decision.allowed() && path.equals("/api/v1/auth/register"))
                decision = limiter.tryAcquire("register-ip:" + ip, limits.registerPerIp());
        } else if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            String user = token.getToken().getSubject();
            decision = limiter.tryAcquire("user:" + user, limits.apiPerUser());
            if (decision.allowed() && WRITES.contains(request.getMethod()))
                decision = limiter.tryAcquire("user-writes:" + user, limits.writesPerUser());
        } else {
            decision = limiter.tryAcquire("anonymous-ip:" + ip, limits.anonymousPerIp());
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitedException refused = new RateLimitedException(decision.retryAfter());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, refused.getMessage());
        problem.setTitle("Too many requests");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(refused.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
