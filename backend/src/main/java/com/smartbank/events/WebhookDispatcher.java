package com.smartbank.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delivers queued webhook events over HTTP, at least once.
 *
 * - Claiming: a short transaction leases a batch of due deliveries with
 *   FOR UPDATE SKIP LOCKED, so several app instances can run this safely. The
 *   HTTP call happens outside any database transaction.
 * - Success is any 2xx response. Anything else (status, timeout, connection
 *   error) is retried with exponential backoff and jitter; after the last attempt
 *   the delivery is marked FAILED (dead letter).
 * - If the process dies mid-delivery, the lease expires and the delivery is
 *   retried. Receivers must therefore de-duplicate on the event id.
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH = 20;
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookUrlGuard guard;
    private final SecretCipher cipher;
    private final Clock clock;
    private final HttpClient http;
    private final Duration baseBackoff;
    private final int maxAttempts;
    private final Duration requestTimeout;

    public WebhookDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, WebhookUrlGuard guard,
                             SecretCipher cipher,
                             @Value("${smartbank.webhooks.base-backoff:PT10S}") Duration baseBackoff,
                             @Value("${smartbank.webhooks.max-attempts:8}") int maxAttempts,
                             @Value("${smartbank.webhooks.request-timeout:PT10S}") Duration requestTimeout) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.guard = guard;
        this.cipher = cipher;
        this.clock = clock;
        this.baseBackoff = baseBackoff;
        this.maxAttempts = maxAttempts;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${smartbank.webhooks.poll-interval:PT1S}")
    public void run() {
        try {
            deliverDue(clock.instant());
        } catch (RuntimeException e) {
            log.warn("Webhook delivery run failed", e);
        }
    }

    record Claimed(UUID deliveryId, int attempt, UUID endpointId, String url, String secretEncrypted,
                   String previousSecretEncrypted, Instant previousSecretExpiresAt,
                   UUID eventId, String eventType, String payload, Instant eventCreatedAt) { }

    /** Delivers everything due at {@code now}; returns the number of attempts made. */
    public int deliverDue(Instant now) {
        int attempted = 0;
        List<Claimed> batch;
        do {
            batch = claim(now);
            for (Claimed delivery : batch) deliver(delivery, now);
            attempted += batch.size();
        } while (batch.size() == BATCH);
        return attempted;
    }

    private List<Claimed> claim(Instant now) {
        // A single UPDATE statement is its own transaction; the row locks from
        // FOR UPDATE SKIP LOCKED keep concurrent workers from claiming the same rows.
        return jdbc.query("""
                UPDATE webhook_deliveries d
                SET locked_until = ?, attempts = d.attempts + 1
                FROM webhook_endpoints e, outbox_events o
                WHERE e.id = d.endpoint_id AND o.id = d.event_id
                  AND d.id IN (SELECT id FROM webhook_deliveries
                               WHERE status = 'PENDING' AND next_attempt_at <= ?
                                 AND (locked_until IS NULL OR locked_until <= ?)
                               ORDER BY next_attempt_at
                               LIMIT ?
                               FOR UPDATE SKIP LOCKED)
                RETURNING d.id, d.attempts, e.id AS endpoint_id, e.url, e.secret_encrypted,
                          e.previous_secret_encrypted, e.previous_secret_expires_at,
                          o.id AS event_id, o.event_type, o.payload, o.created_at AS event_created_at""",
                (rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getInt("attempts"),
                        rs.getObject("endpoint_id", UUID.class), rs.getString("url"), rs.getString("secret_encrypted"),
                        rs.getString("previous_secret_encrypted"),
                        rs.getTimestamp("previous_secret_expires_at") == null ? null
                                : rs.getTimestamp("previous_secret_expires_at").toInstant(),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("event_created_at").toInstant()),
                Timestamp.from(now.plus(LEASE)), Timestamp.from(now), Timestamp.from(now), BATCH);
    }

    private void deliver(Claimed d, Instant now) {
        String body = envelope(d);
        Integer status = null;
        String error = null;
        try {
            URI target = URI.create(d.url());
            // Re-check where the host resolves now: it may have changed since registration.
            guard.checkResolved(target);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.signatureHeader(secrets(d, now), now.getEpochSecond(), body))
                    .header(WebhookSigner.EVENT_ID_HEADER, d.eventId().toString())
                    .header(WebhookSigner.DELIVERY_ID_HEADER, d.deliveryId().toString())
                    .header(WebhookSigner.ATTEMPT_HEADER, String.valueOf(d.attempt()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            status = response.statusCode();
            if (status >= 200 && status < 300) {
                jdbc.update("""
                        UPDATE webhook_deliveries SET status = 'DELIVERED', delivered_at = ?, last_status_code = ?,
                            last_error = NULL, locked_until = NULL WHERE id = ?""",
                        Timestamp.from(now), status, d.deliveryId());
                return;
            }
            error = "HTTP " + status;
        } catch (WebhookUrlGuard.BlockedUrlException e) {
            error = "Blocked: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        boolean exhausted = d.attempt() >= maxAttempts;
        jdbc.update("""
                UPDATE webhook_deliveries SET status = ?, next_attempt_at = ?, last_status_code = ?,
                    last_error = ?, locked_until = NULL WHERE id = ?""",
                exhausted ? "FAILED" : "PENDING", Timestamp.from(exhausted ? now : now.plus(backoff(d.attempt()))),
                status, error.length() > 500 ? error.substring(0, 500) : error, d.deliveryId());
        if (exhausted) log.warn("Webhook delivery {} failed permanently after {} attempts: {}", d.deliveryId(), d.attempt(), error);
    }

    /** The current secret, plus the previous one while a rotation's overlap lasts. */
    private List<String> secrets(Claimed d, Instant now) {
        String endpoint = d.endpointId().toString();
        String current = cipher.decrypt(d.secretEncrypted(), endpoint);
        if (d.previousSecretEncrypted() != null && d.previousSecretExpiresAt().isAfter(now))
            return List.of(current, cipher.decrypt(d.previousSecretEncrypted(), endpoint));
        return List.of(current);
    }

    /** baseBackoff * 2^(attempt-1), plus up to 20% jitter so retries from many deliveries spread out. */
    Duration backoff(int attempt) {
        long millis = baseBackoff.toMillis() << Math.min(attempt - 1, 20);
        return Duration.ofMillis(millis + (long) (millis * 0.2 * ThreadLocalRandom.current().nextDouble()));
    }

    /** The same event always produces the same body, so every attempt sends identical bytes. */
    private String envelope(Claimed d) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("id", d.eventId().toString());
            envelope.put("type", d.eventType());
            envelope.put("createdAt", d.eventCreatedAt().toString());
            envelope.set("data", mapper.readTree(d.payload()));
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build webhook body for event " + d.eventId(), e);
        }
    }
}
