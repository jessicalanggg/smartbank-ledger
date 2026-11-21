package com.smartbank.ratelimit;

import java.time.Duration;

public class RateLimitedException extends RuntimeException {
    private final Duration retryAfter;
    public RateLimitedException(Duration retryAfter) {
        super("Too many requests; retry after " + Math.max(1, (retryAfter.toMillis() + 999) / 1000) + " s");
        this.retryAfter = retryAfter;
    }
    public long retryAfterSeconds() { return Math.max(1, (retryAfter.toMillis() + 999) / 1000); }
}
