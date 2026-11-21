package com.smartbank.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Appends an event to the outbox and queues a delivery for every active webhook
 * endpoint subscribed to it, inside the caller's transaction. The event therefore
 * commits or rolls back together with the change it describes: there is never an
 * event for a transfer that did not happen, nor a transfer without its event.
 *
 * An endpoint receives an event when its owner owns one of the event's accounts
 * and it is subscribed to the event type (or to all types).
 */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Inserts one event. Parameters: {@link PreparedEvent#eventParams()}. */
    public static final String INSERT_EVENT = """
            INSERT INTO outbox_events (id, event_type, aggregate_id, account_ids, payload, created_at)
            VALUES (?, ?, ?, string_to_array(?, ',')::uuid[], ?, ?)""";

    /** Queues deliveries to subscribed endpoints. Parameters: {@link PreparedEvent#fanOutParams()}. */
    public static final String FAN_OUT = """
            INSERT INTO webhook_deliveries (id, event_id, endpoint_id, status, attempts, next_attempt_at, created_at)
            SELECT gen_random_uuid(), ?, e.id, 'PENDING', 0, ?, ?
            FROM webhook_endpoints e
            WHERE e.active
              AND (e.event_types IS NULL OR ? = ANY (e.event_types))
              AND EXISTS (SELECT 1 FROM accounts a
                          WHERE a.id = ANY (string_to_array(?, ',')::uuid[]) AND a.owner_user_id = e.user_id)""";

    /** An event ready to be written, alone or as part of a larger statement. */
    public record PreparedEvent(UUID id, String type, UUID aggregateId, String accounts, String payload, Timestamp at) {
        public Object[] eventParams() { return new Object[] { id, type, aggregateId, accounts, payload, at }; }
        public Object[] fanOutParams() { return new Object[] { id, at, at, type, accounts }; }
    }

    public PreparedEvent prepare(String eventType, UUID aggregateId, Object payload, UUID... accountIds) {
        if (!EventTypes.ALL.contains(eventType)) throw new IllegalArgumentException("Unknown event type " + eventType);
        Timestamp now = Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        String accounts = Stream.of(accountIds).filter(Objects::nonNull).map(UUID::toString).distinct()
                .collect(Collectors.joining(","));
        return new PreparedEvent(UUID.randomUUID(), eventType, aggregateId, accounts, write(payload), now);
    }

    /** Writes the event and its deliveries in one statement, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, UUID aggregateId, Object payload, UUID... accountIds) {
        PreparedEvent event = prepare(eventType, aggregateId, payload, accountIds);
        jdbc.update("WITH event AS (" + INSERT_EVENT + ")\n" + FAN_OUT,
                concat(event.eventParams(), event.fanOutParams()));
        return event.id();
    }

    static Object[] concat(Object[]... parts) {
        return Stream.of(parts).flatMap(Stream::of).toArray();
    }

    private String write(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event payload", e);
        }
    }
}
