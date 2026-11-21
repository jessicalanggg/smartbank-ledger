package com.smartbank.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class WebhookEndpointService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WebhookEndpointService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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
        URI uri = validateUrl(request.url());
        List<String> types = request.eventTypes() == null || request.eventTypes().isEmpty() ? null
                : request.eventTypes().stream().distinct().sorted().toList();
        if (types != null && !EventTypes.ALL.containsAll(types))
            throw new IllegalArgumentException("Unknown event type; allowed: " + new TreeSet<>(EventTypes.ALL));
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String secret = "whsec_" + HexFormat.of().formatHex(bytes);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO webhook_endpoints (id, user_id, url, secret, event_types, active, created_at)
                    VALUES (?, ?, ?, ?, ?, TRUE, ?)""");
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, uri.toString());
            ps.setString(4, secret);
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

    private static URI validateUrl(String url) {
        if (url == null || url.length() > 2048) throw new IllegalArgumentException("A URL of at most 2048 characters is required");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("URL must be an absolute http or https URL");
        return uri;
    }
}
