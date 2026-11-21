package com.smartbank.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class WebhookEndpointService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WebhookUrlGuard guard;
    private final SecretCipher cipher;

    public WebhookEndpointService(JdbcTemplate jdbc, Clock clock, WebhookUrlGuard guard, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.guard = guard;
        this.cipher = cipher;
    }

    public record RotatedSecret(UUID endpointId, String secret, Instant previousSecretExpiresAt) { }

    private static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    public record CreateRequest(String url, List<String> eventTypes) { }

    public record EndpointResponse(UUID id, String url, List<String> eventTypes, boolean active, Instant createdAt,
                                   String secret) { }

    public record DeliveryResponse(UUID id, UUID eventId, String eventType, String status, int attempts,
                                   Instant nextAttemptAt, Integer lastStatusCode, String lastError,
                                   Instant deliveredAt, Instant createdAt) { }

    /** The secret is returned only here, once. */
    @Transactional
    public EndpointResponse create(UUID userId, CreateRequest request) {
        URI uri = guard.validate(request.url());
        List<String> types = request.eventTypes() == null || request.eventTypes().isEmpty() ? null
                : request.eventTypes().stream().distinct().sorted().toList();
        if (types != null && !EventTypes.ALL.containsAll(types))
            throw new IllegalArgumentException("Unknown event type; allowed: " + new TreeSet<>(EventTypes.ALL));
        String secret = newSecret();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO webhook_endpoints (id, user_id, url, secret_encrypted, event_types, active, created_at)
                    VALUES (?, ?, ?, ?, ?, TRUE, ?)""");
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, uri.toString());
            ps.setString(4, cipher.encrypt(secret, id.toString()));
            Array array = types == null ? null : con.createArrayOf("varchar", types.toArray());
            ps.setArray(5, array);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        });
        return new EndpointResponse(id, uri.toString(), types, true, now, secret);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> list(UUID userId) {
        return jdbc.query("""
                SELECT id, url, event_types, active, created_at FROM webhook_endpoints
                WHERE user_id = ? ORDER BY created_at""",
                (rs, n) -> {
                    Array types = rs.getArray("event_types");
                    return new EndpointResponse(rs.getObject("id", UUID.class), rs.getString("url"),
                            types == null ? null : List.of((String[]) types.getArray()),
                            rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(), null);
                }, userId);
    }

    /**
     * Replaces the signing secret. The old one keeps signing alongside the new one
     * until {@code overlap} has passed, so receivers can switch without rejecting
     * deliveries. The new secret is returned only here, once.
     */
    @Transactional
    public RotatedSecret rotateSecret(UUID userId, UUID endpointId, Duration overlap) {
        requireOwned(userId, endpointId);
        if (overlap.isNegative() || overlap.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("Overlap must be between 0 and 7 days");
        String secret = newSecret();
        Instant expires = clock.instant().plus(overlap).truncatedTo(ChronoUnit.MICROS);
        String current = jdbc.queryForObject("SELECT secret_encrypted FROM webhook_endpoints WHERE id = ? FOR UPDATE",
                String.class, endpointId);
        boolean keepOld = !overlap.isZero();
        jdbc.update("""
                UPDATE webhook_endpoints SET secret_encrypted = ?, previous_secret_encrypted = ?,
                    previous_secret_expires_at = ? WHERE id = ?""",
                cipher.encrypt(secret, endpointId.toString()), keepOld ? current : null,
                keepOld ? Timestamp.from(expires) : null, endpointId);
        return new RotatedSecret(endpointId, secret, keepOld ? expires : null);
    }

    /**
     * Queues a delivery again, typically one that was dead-lettered after the
     * receiver was fixed. It starts a fresh series of attempts.
     */
    @Transactional
    public DeliveryResponse resend(UUID userId, UUID endpointId, UUID deliveryId) {
        requireActive(userId, endpointId);
        int queued = jdbc.update("""
                UPDATE webhook_deliveries SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                    locked_until = NULL, delivered_at = NULL, last_error = NULL, last_status_code = NULL
                WHERE id = ? AND endpoint_id = ? AND status <> 'PENDING'""",
                Timestamp.from(clock.instant()), deliveryId, endpointId);
        if (queued == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE id = ? AND endpoint_id = ?",
                    Integer.class, deliveryId, endpointId);
            if (exists == null || exists == 0) throw new WebhookEndpointNotFoundException(deliveryId);
        }
        return deliveries(userId, endpointId).stream().filter(d -> d.id().equals(deliveryId)).findFirst().orElseThrow();
    }

    /** Queues every dead-lettered delivery of the endpoint again; returns how many. */
    @Transactional
    public int resendFailed(UUID userId, UUID endpointId) {
        requireActive(userId, endpointId);
        return jdbc.update("""
                UPDATE webhook_deliveries SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                    locked_until = NULL, last_error = NULL, last_status_code = NULL
                WHERE endpoint_id = ? AND status = 'FAILED'""", Timestamp.from(clock.instant()), endpointId);
    }

    private void requireActive(UUID userId, UUID endpointId) {
        requireOwned(userId, endpointId);
        Boolean active = jdbc.queryForObject("SELECT active FROM webhook_endpoints WHERE id = ?", Boolean.class, endpointId);
        if (!Boolean.TRUE.equals(active))
            throw new com.smartbank.transaction.BankingException("Webhook endpoint is deactivated");
    }

    /** Stops new deliveries to the endpoint; queued ones are no longer attempted. */
    @Transactional
    public void deactivate(UUID userId, UUID endpointId) {
        requireOwned(userId, endpointId);
        jdbc.update("UPDATE webhook_endpoints SET active = FALSE WHERE id = ?", endpointId);
        jdbc.update("""
                UPDATE webhook_deliveries SET status = 'FAILED', last_error = 'Endpoint deactivated', locked_until = NULL
                WHERE endpoint_id = ? AND status = 'PENDING'""", endpointId);
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> deliveries(UUID userId, UUID endpointId) {
        requireOwned(userId, endpointId);
        return jdbc.query("""
                SELECT d.id, d.event_id, o.event_type, d.status, d.attempts, d.next_attempt_at, d.last_status_code,
                       d.last_error, d.delivered_at, d.created_at
                FROM webhook_deliveries d JOIN outbox_events o ON o.id = d.event_id
                WHERE d.endpoint_id = ? ORDER BY d.created_at DESC LIMIT 100""",
                (rs, n) -> new DeliveryResponse(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"),
                        rs.getTimestamp("next_attempt_at").toInstant(), (Integer) rs.getObject("last_status_code"),
                        rs.getString("last_error"),
                        rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                endpointId);
    }

    private void requireOwned(UUID userId, UUID endpointId) {
        Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_endpoints WHERE id = ? AND user_id = ?", Integer.class, endpointId, userId);
        if (owned == null || owned == 0) throw new WebhookEndpointNotFoundException(endpointId);
    }
}
