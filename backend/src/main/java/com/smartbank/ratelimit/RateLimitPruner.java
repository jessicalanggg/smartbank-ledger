package com.smartbank.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;

/** Deletes buckets idle for a day; they would have refilled to full long before. */
@Component
public class RateLimitPruner {
    private final RateLimiter limiter;
    private final Clock clock;

    public RateLimitPruner(RateLimiter limiter, Clock clock) {
        this.limiter = limiter;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${smartbank.rate-limits.prune-interval:PT1H}")
    public void run() {
        limiter.pruneIdle(clock.instant().minus(Duration.ofDays(1)));
    }
}
