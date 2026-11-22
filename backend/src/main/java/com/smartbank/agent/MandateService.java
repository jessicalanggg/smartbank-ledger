package com.smartbank.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartbank.account.AccountNotFoundException;
import com.smartbank.common.Currencies;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Creating and reading agent spending mandates. A mandate's limits never change
 * after creation; to change them, revoke it and create another.
 */
@Service
public class MandateService {
    static final int DEFAULT_DAYS = 30;
    static final int MAX_PAYEES = 50;

    private final JdbcTemplate jdbc;
    private final Currencies currencies;
    private final Clock clock;

    public MandateService(JdbcTemplate jdbc, Currencies currencies, Clock clock) {
        this.jdbc = jdbc;
        this.currencies = currencies;
        this.clock = clock;
    }

    public record CreateMandate(UUID accountId, String name, BigDecimal perPaymentLimit, BigDecimal dailyLimit,
                                List<String> allowedPayeeAccountNumbers, Integer expiresInDays, Boolean askAboveLimit) { }

    /** A mandate as its owner and its agent see it. allowedPayees empty means any payee. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mandate(UUID id, String name, UUID accountId, String currency, BigDecimal perPaymentLimit,
                          BigDecimal dailyLimit, BigDecimal spentToday, BigDecimal remainingToday, boolean askAboveLimit,
                          List<String> allowedPayees, String keyPrefix, String status,
                          Instant createdAt, Instant expiresAt, Instant revokedAt) { }

    /** Returned once, at creation: the only time the API key is shown. */
    public record CreatedMandate(Mandate mandate, String apiKey) { }

    @Transactional
    public CreatedMandate create(UUID userId, CreateMandate request) {
        if (request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("A mandate needs a name");
        String currency = jdbc.query("SELECT currency FROM accounts WHERE id = ?",
                (rs, n) -> rs.getString(1), request.accountId()).stream().findFirst()
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        currencies.requireValidAmount(request.perPaymentLimit(), currency);
        currencies.requireValidAmount(request.dailyLimit(), currency);
        if (request.dailyLimit().compareTo(request.perPaymentLimit()) < 0)
            throw new IllegalArgumentException("The daily limit must be at least the per-payment limit");
        int days = request.expiresInDays() == null ? DEFAULT_DAYS : request.expiresInDays();
        if (days < 1 || days > 365) throw new IllegalArgumentException("A mandate lasts between 1 and 365 days");

        List<String> numbers = request.allowedPayeeAccountNumbers() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.allowedPayeeAccountNumbers().stream().map(String::trim).toList()));
        if (numbers.size() > MAX_PAYEES)
            throw new IllegalArgumentException("A mandate allows at most " + MAX_PAYEES + " payees");
        List<UUID> payees = new ArrayList<>();
        for (String number : numbers) {
            var payee = jdbc.query("SELECT id, currency FROM accounts WHERE account_number = ?",
                    (rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2) }, number);
            if (payee.isEmpty()) throw new IllegalArgumentException("Payee account " + number + " not found");
            UUID id = (UUID) payee.get(0)[0];
            if (id.equals(request.accountId())) throw new IllegalArgumentException("A mandate cannot pay its own account");
            if (!currency.equals(payee.get(0)[1]))
                throw new IllegalArgumentException("Payee account " + number + " is not in " + currency);
            payees.add(id);
        }

        String key = AgentKeys.generate();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO agent_mandates (id, user_id, account_id, name, key_hash, key_prefix, currency,
                    per_payment_limit, daily_limit, ask_above_limit, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, request.accountId(), request.name().trim(), AgentKeys.hash(key), AgentKeys.displayPrefix(key),
                currency, request.perPaymentLimit(), request.dailyLimit(),
                request.askAboveLimit() == null || request.askAboveLimit(),
                Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(days))));
        jdbc.batchUpdate("INSERT INTO agent_mandate_payees (mandate_id, account_id) VALUES (?, ?)",
                payees.stream().map(p -> new Object[] { id, p }).toList());
        return new CreatedMandate(find(id), key);
    }

    @Transactional(readOnly = true)
    public List<Mandate> forUser(UUID userId) {
        return jdbc.query(SELECT + " WHERE m.user_id = ? ORDER BY m.created_at DESC LIMIT 100",
                this::map, today(), userId);
    }

    /** The mandate, if {@code userId} owns it. */
    @Transactional(readOnly = true)
    public Mandate get(UUID userId, UUID mandateId) {
        var found = jdbc.query(SELECT + " WHERE m.id = ? AND m.user_id = ?", this::map, today(), mandateId, userId);
        if (found.isEmpty()) throw new MandateNotFoundException(mandateId);
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public Mandate find(UUID mandateId) {
        var found = jdbc.query(SELECT + " WHERE m.id = ?", this::map, today(), mandateId);
        if (found.isEmpty()) throw new MandateNotFoundException(mandateId);
        return found.get(0);
    }

    private static final String SELECT = """
            SELECT m.*, COALESCE(u.auto_spent + u.approved_spent, 0) AS spent_today,
                   ARRAY(SELECT a.account_number FROM agent_mandate_payees p JOIN accounts a ON a.id = p.account_id
                         WHERE p.mandate_id = m.id ORDER BY a.account_number) AS payees
            FROM agent_mandates m
            LEFT JOIN agent_mandate_usage u ON u.mandate_id = m.id AND u.day = ?""";

    private Mandate map(ResultSet rs, int n) throws SQLException {
        Instant expires = rs.getTimestamp("expires_at").toInstant();
        Timestamp revoked = rs.getTimestamp("revoked_at");
        String status = revoked != null ? "REVOKED" : clock.instant().isBefore(expires) ? "ACTIVE" : "EXPIRED";
        BigDecimal daily = rs.getBigDecimal("daily_limit");
        BigDecimal spent = rs.getBigDecimal("spent_today");
        return new Mandate(rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("account_id", UUID.class),
                rs.getString("currency"), rs.getBigDecimal("per_payment_limit"), daily, spent,
                daily.subtract(spent).max(BigDecimal.ZERO).setScale(2), rs.getBoolean("ask_above_limit"),
                Arrays.asList((String[]) rs.getArray("payees").getArray()), rs.getString("key_prefix"), status,
                rs.getTimestamp("created_at").toInstant(), expires, revoked == null ? null : revoked.toInstant());
    }

    /** Mandate usage is counted per UTC day. */
    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
