package com.smartbank.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/** Deletes expired idempotency keys in small batches. */
@Component
public class IdempotencyKeyPruner {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPruner.class);
    private static final int BATCH = 1_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyKeyPruner(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${smartbank.idempotency.prune-interval:PT1H}")
    public void run() {
        int pruned = pruneExpired(clock.instant());
        if (pruned > 0) log.info("Pruned {} expired idempotency keys", pruned);
    }

    public int pruneExpired(Instant now) {
        int total = 0, deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM idempotency_keys WHERE ctid = ANY (ARRAY(
                        SELECT ctid FROM idempotency_keys WHERE expires_at <= ? LIMIT ?))""",
                    Timestamp.from(now), BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
