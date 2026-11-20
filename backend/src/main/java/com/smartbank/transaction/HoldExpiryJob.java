package com.smartbank.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Releases holds that passed their expiry without being posted or voided. */
@Component
public class HoldExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final int BATCH = 100;

    private final HoldService holds;
    private final Clock clock;

    public HoldExpiryJob(HoldService holds, Clock clock) {
        this.holds = holds;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${smartbank.holds.expiry-interval:PT30S}")
    public void run() {
        int expired = expireDue(clock.instant());
        if (expired > 0) log.info("Expired {} holds", expired);
    }

    /** Expires every hold due at {@code now}, one transaction per hold. */
    public int expireDue(Instant now) {
        int expired = 0;
        while (true) {
            List<UUID> due = holds.dueForExpiry(now, BATCH);
            int before = expired;
            for (UUID id : due) {
                try {
                    if (holds.expire(id, now)) expired++;
                } catch (RuntimeException e) {
                    log.warn("Could not expire hold {}", id, e);
                }
            }
            // Stop when the queue is drained, or when a batch made no progress.
            if (due.size() < BATCH || expired == before) return expired;
        }
    }
}
