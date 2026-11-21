package com.smartbank.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartbank.account.AccountNotFoundException;
import com.smartbank.auth.AccessService;
import com.smartbank.common.ApiExceptionHandler;
import com.smartbank.transaction.BankingException;
import com.smartbank.transaction.TransactionNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Client idempotency keys for money-moving endpoints, in the style of
 * Stripe-like payment APIs:
 *
 * - Keys come from the {@value #HEADER} header (or the legacy {@code requestKey}
 *   body field) and are scoped to the authenticated user.
 * - The first request's response, success or business error, is stored as exact
 *   bytes and replayed for every retry with the same key, marked with
 *   {@value #REPLAYED_HEADER}: true.
 * - Reusing a key for a different request is rejected with 422.
 * - Keys expire after the retention window (default 24 hours) and are pruned;
 *   after that the key can be used for a new request.
 *
 * Exactly-once posting does not depend on this table being written: each client
 * key maps to a server-generated operation key, which the ledger stores under a
 * UNIQUE constraint. If the server stops after posting but before storing the
 * response, the retry runs the same operation key and gets the original
 * transaction back.
 *
 * Not stored, so a retry runs again: validation errors (400), authorization
 * failures (403, checked before this runs), and unexpected errors (5xx).
 */
@Service
public class IdempotencyService {
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 255;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccessService access;
    private final ApiExceptionHandler errors;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration retention;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate transactions, AccessService access,
                              ApiExceptionHandler errors, ObjectMapper mapper, Clock clock,
                              @Value("${smartbank.idempotency.retention:PT24H}") Duration retention) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.access = access;
        this.errors = errors;
        this.mapper = mapper;
        this.clock = clock;
        this.retention = retention;
    }

    /**
     * Runs {@code operation} at most once per (user, key) within the retention window.
     *
     * @param headerKey   the Idempotency-Key header, or null
     * @param bodyKey     the requestKey body field, or null
     * @param fingerprint identifies the request's content; see {@link #fingerprint}
     * @param operation   receives the operation key to pass to the service as its request key
     */
    public ResponseEntity<String> execute(String headerKey, String bodyKey, String fingerprint,
                                          HttpStatus successStatus, Function<String, Object> operation) {
        String key = resolveKey(headerKey, bodyKey);
        UUID user = access.currentUserId();
        Claim claim = claim(user, key, fingerprint);
        if (claim.completed())
            return respond(claim.responseStatus(), claim.responseBody(), true);

        int status;
        Object body;
        try {
            body = operation.apply(claim.operationKey().toString());
            status = successStatus.value();
        } catch (BankingException e) {
            body = errors.banking(e);
            status = HttpStatus.CONFLICT.value();
        } catch (AccountNotFoundException e) {
            body = errors.notFound(e);
            status = HttpStatus.NOT_FOUND.value();
        } catch (TransactionNotFoundException e) {
            body = errors.transactionNotFound(e);
            status = HttpStatus.NOT_FOUND.value();
        }
        return complete(user, key, claim.operationKey(), status, write(body));
    }

    record Claim(UUID operationKey, boolean completed, int responseStatus, String responseBody) { }

    /**
     * One round trip in the common case: insert the key, or read the live one. Falls
     * back to {@link #claimSlowly} when the stored key has expired, or when a
     * simultaneous first use committed after this statement's snapshot.
     */
    private Claim claim(UUID user, String key, String fingerprint) {
        Instant now = clock.instant();
        List<StoredKey> rows = jdbc.query("""
                WITH inserted AS (
                    INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, operation_key, state, created_at, expires_at)
                    VALUES (?, ?, ?, ?, 'STARTED', ?, ?)
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    RETURNING request_hash, operation_key, state, response_status, response_body, expires_at)
                SELECT * FROM inserted
                UNION ALL
                SELECT request_hash, operation_key, state, response_status, response_body, expires_at
                FROM idempotency_keys
                WHERE user_id = ? AND idempotency_key = ? AND NOT EXISTS (SELECT 1 FROM inserted)""",
                (rs, n) -> new StoredKey(rs.getString("request_hash"), rs.getObject("operation_key", UUID.class),
                        rs.getString("state"), rs.getInt("response_status"), rs.getString("response_body"),
                        rs.getTimestamp("expires_at").toInstant()),
                user, key, fingerprint, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now.plus(retention)),
                user, key);
        if (rows.isEmpty() || !rows.get(0).expiresAt().isAfter(now))
            return transactions.execute(status -> claimSlowly(user, key, fingerprint));
        return rows.get(0).toClaim(fingerprint);
    }

    record StoredKey(String requestHash, UUID operationKey, String state, int responseStatus, String responseBody,
                     Instant expiresAt) {
        Claim toClaim(String fingerprint) {
            if (!fingerprint.equals(requestHash)) throw new IdempotencyKeyMismatchException();
            return new Claim(operationKey, "COMPLETED".equals(state), responseStatus, responseBody);
        }
    }

    /** Removes an expired key and claims it again, in one transaction. */
    private Claim claimSlowly(UUID user, String key, String fingerprint) {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?",
                user, key, Timestamp.from(now));
        // A simultaneous first use of the same key waits here until the other commits, then does nothing.
        jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, operation_key, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'STARTED', ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING""",
                user, key, fingerprint, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now.plus(retention)));
        return jdbc.queryForObject("""
                SELECT request_hash, operation_key, state, response_status, response_body
                FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?""",
                (rs, n) -> {
                    if (!fingerprint.equals(rs.getString("request_hash")))
                        throw new IdempotencyKeyMismatchException();
                    return new Claim(rs.getObject("operation_key", UUID.class),
                            "COMPLETED".equals(rs.getString("state")),
                            rs.getInt("response_status"), rs.getString("response_body"));
                }, user, key);
    }

    /** Stores the response unless a simultaneous duplicate already did; returns whichever is stored. */
    private ResponseEntity<String> complete(UUID user, String key, UUID operationKey, int status, String body) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys SET state = 'COMPLETED', response_status = ?, response_body = ?, completed_at = ?
                WHERE user_id = ? AND idempotency_key = ? AND operation_key = ? AND state = 'STARTED'""",
                status, body, Timestamp.from(clock.instant()), user, key, operationKey);
        if (updated == 1)
            return respond(status, body, false);
        var stored = jdbc.query("""
                SELECT response_status, response_body FROM idempotency_keys
                WHERE user_id = ? AND idempotency_key = ? AND operation_key = ? AND state = 'COMPLETED'""",
                (rs, n) -> respond(rs.getInt("response_status"), rs.getString("response_body"), true),
                user, key, operationKey);
        return stored.isEmpty() ? respond(status, body, false) : stored.get(0);
    }

    private static ResponseEntity<String> respond(int status, String body, boolean replayed) {
        var response = ResponseEntity.status(status)
                .contentType(status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
        if (replayed) response.header(REPLAYED_HEADER, "true");
        return response.body(body);
    }

    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
    }

    static String resolveKey(String headerKey, String bodyKey) {
        boolean hasHeader = headerKey != null && !headerKey.isBlank();
        boolean hasBody = bodyKey != null && !bodyKey.isBlank();
        if (hasHeader && hasBody && !headerKey.equals(bodyKey))
            throw new InvalidIdempotencyKeyException("Idempotency-Key header and requestKey differ");
        if (!hasHeader && !hasBody)
            throw new InvalidIdempotencyKeyException("An Idempotency-Key header (or requestKey field) is required");
        String key = hasHeader ? headerKey : bodyKey;
        if (key.length() > MAX_KEY_LENGTH)
            throw new InvalidIdempotencyKeyException("Idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
        return key;
    }

    /**
     * SHA-256 of the operation name and its parameters. Amounts are normalized to two
     * decimal places, so "10", "10.0" and "10.00" are the same request.
     */
    public static String fingerprint(String operation, Object... parts) {
        StringBuilder canonical = new StringBuilder(operation);
        for (Object part : parts) {
            canonical.append('\u001f');
            canonical.append(part instanceof BigDecimal amount
                    ? amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                    : String.valueOf(part));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
