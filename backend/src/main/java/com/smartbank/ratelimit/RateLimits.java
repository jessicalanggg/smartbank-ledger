package com.smartbank.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limits, configured under {@code smartbank.rate-limits} (defaults in
 * application.yml). Each limit is a burst {@code capacity} plus one more request
 * every {@code refill-every}.
 *
 * @param loginPerEmail  failed logins per email address: brute force on one account
 * @param authPerIp      login and registration requests per client IP: attacks across accounts
 * @param registerPerIp  registrations per client IP
 * @param anonymousPerIp requests without a valid token, per client IP
 * @param apiPerUser     authenticated requests per user
 * @param writesPerUser  authenticated writes (POST, PUT, PATCH, DELETE) per user
 */
@ConfigurationProperties("smartbank.rate-limits")
public record RateLimits(
        boolean enabled,
        RateLimiter.Limit loginPerEmail,
        RateLimiter.Limit authPerIp,
        RateLimiter.Limit registerPerIp,
        RateLimiter.Limit anonymousPerIp,
        RateLimiter.Limit apiPerUser,
        RateLimiter.Limit writesPerUser) {
}
