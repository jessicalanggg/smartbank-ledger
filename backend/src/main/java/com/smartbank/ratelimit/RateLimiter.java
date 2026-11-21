package com.smartbank.ratelimit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Token-bucket rate limiting backed by PostgreSQL, so every app instance shares
 * the same buckets. Taking a token is one atomic UPDATE in the common case: the
 * refill since the last request and the decrement happen in the same statement,
 * under the bucket's row lock.
 */
@Component
public class RateLimiter {
    /** Up to {@code capacity} requests at once, then one more every {@code refillEvery}. */
    public record Limit(int capacity, Duration refillEvery) {
        double refillSeconds() { return refillEvery.toNanos() / 1e9; }
    }

    public record Decision(boolean allowed, Duration retryAfter) {
        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    private static final String REFILLED =
            "LEAST(?, tokens + GREATEST(0, EXTRACT(EPOCH FROM (?::timestamptz - updated_at))) / ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Decision tryAcquire(String key, Limit limit) {
        Timestamp now = Timestamp.from(clock.instant());
        for (int attempt = 0; attempt < 2; attempt++) {
            // Existing bucket with at least one token after refill: take it.
            int taken = jdbc.update("UPDATE rate_limit_buckets SET tokens = " + REFILLED + " - 1, "
                            + "updated_at = GREATEST(updated_at, ?::timestamptz) "
                            + "WHERE bucket_key = ? AND " + REFILLED + " >= 1",
                    limit.capacity(), now, limit.refillSeconds(), now, key,
                    limit.capacity(), now, limit.refillSeconds());
            if (taken == 1) return Decision.ALLOWED;
            // No bucket yet: create it full, minus this request's token.
            int created = jdbc.update("INSERT INTO rate_limit_buckets (bucket_key, tokens, updated_at) "
                    + "VALUES (?, ?, ?) ON CONFLICT (bucket_key) DO NOTHING", key, limit.capacity() - 1.0, now);
            if (created == 1) return Decision.ALLOWED;
            // The bucket exists: either it is empty, or another request created it a moment ago (retry once).
        }
        List<Double> tokens = jdbc.queryForList(
                "SELECT " + REFILLED + " FROM rate_limit_buckets WHERE bucket_key = ?", Double.class,
                limit.capacity(), now, limit.refillSeconds(), key);
        double available = tokens.isEmpty() ? 0 : tokens.get(0);
        long waitMillis = (long) Math.ceil(Math.max(0, 1 - available) * limit.refillEvery().toMillis());
        return new Decision(false, Duration.ofMillis(Math.max(waitMillis, 1)));
    }

    /** Gives a token back, for example after a successful login. */
    public void release(String key, Limit limit) {
        jdbc.update("UPDATE rate_limit_buckets SET tokens = LEAST(?, tokens + 1) WHERE bucket_key = ?",
                limit.capacity(), key);
    }

    /** Deletes buckets untouched since {@code before}; they would be full again by now. */
    public int pruneIdle(Instant before) {
        return jdbc.update("DELETE FROM rate_limit_buckets WHERE updated_at < ?", Timestamp.from(before));
    }
}
