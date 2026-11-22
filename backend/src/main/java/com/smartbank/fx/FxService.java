package com.smartbank.fx;

import com.smartbank.account.AccountNotFoundException;
import com.smartbank.common.Currencies;
import com.smartbank.transaction.BankingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * FX rates and quotes. A rate says how many units of the quote currency one unit of
 * the base currency buys; each direction is set separately, as banks quote different
 * buy and sell rates. A quote locks the current rate and both amounts for a short
 * time; the converted amount is rounded down to the target currency's minor unit.
 */
@Service
public class FxService {
    private final JdbcTemplate jdbc;
    private final Currencies currencies;
    private final Clock clock;
    private final Duration quoteTtl;

    public FxService(JdbcTemplate jdbc, Currencies currencies, Clock clock,
                     @Value("${smartbank.fx.quote-ttl:PT60S}") Duration quoteTtl) {
        this.jdbc = jdbc;
        this.currencies = currencies;
        this.clock = clock;
        this.quoteTtl = quoteTtl;
    }

    public record Rate(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate, Instant setAt) { }

    public record Quote(UUID id, UUID fromAccountId, UUID toAccountId, BigDecimal sellAmount, String sellCurrency,
                        BigDecimal buyAmount, String buyCurrency, BigDecimal rate, Instant expiresAt) { }

    /** The current rate for every pair. */
    @Transactional(readOnly = true)
    public List<Rate> currentRates() {
        return jdbc.query("""
                SELECT DISTINCT ON (base_currency, quote_currency) id, base_currency, quote_currency, rate, created_at
                FROM fx_rates ORDER BY base_currency, quote_currency, created_at DESC, id""",
                (rs, n) -> new Rate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getTimestamp(5).toInstant()));
    }

    @Transactional
    public Rate setRate(String base, String quote, BigDecimal rate, UUID setBy) {
        currencies.minorUnits(base);
        currencies.minorUnits(quote);
        if (base.equals(quote)) throw new IllegalArgumentException("A rate needs two different currencies");
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("The rate must be positive");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO fx_rates (id, base_currency, quote_currency, rate, set_by, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, base, quote, rate.setScale(10, RoundingMode.HALF_EVEN), setBy, Timestamp.from(now));
        return new Rate(id, base, quote, rate.setScale(10, RoundingMode.HALF_EVEN), now);
    }

    /** Locks the current rate for converting {@code amount} out of {@code from} into {@code to}. */
    @Transactional
    public Quote quote(UUID userId, UUID from, UUID to, BigDecimal amount) {
        if (from.equals(to)) throw new BankingException("Source and destination must differ");
        String sell = currency(from), buy = currency(to);
        if (sell.equals(buy)) throw new BankingException("Both accounts are in " + sell + "; use a normal transfer");
        currencies.requireValidAmount(amount, sell);
        var rate = jdbc.query("""
                SELECT id, rate FROM fx_rates WHERE base_currency = ? AND quote_currency = ?
                ORDER BY created_at DESC, id LIMIT 1""",
                (rs, n) -> new Object[] { rs.getObject(1, UUID.class), rs.getBigDecimal(2) }, sell, buy);
        if (rate.isEmpty()) throw new BankingException("No rate is available for " + sell + " to " + buy);
        BigDecimal r = (BigDecimal) rate.get(0)[1];
        BigDecimal bought = amount.multiply(r).setScale(currencies.minorUnits(buy), RoundingMode.DOWN);
        if (bought.signum() <= 0) throw new BankingException("The amount is too small to convert");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS), expires = now.plus(quoteTtl);
        jdbc.update("""
                INSERT INTO fx_quotes (id, user_id, from_account_id, to_account_id, sell_amount, sell_currency,
                                       buy_amount, buy_currency, rate, rate_id, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, from, to, amount, sell, bought, buy, r, rate.get(0)[0], Timestamp.from(now), Timestamp.from(expires));
        return new Quote(id, from, to, amount, sell, bought, buy, r, expires);
    }

    private String currency(UUID account) {
        var c = jdbc.queryForList("SELECT currency FROM accounts WHERE id = ?", String.class, account);
        if (c.isEmpty()) throw new AccountNotFoundException(account);
        return c.get(0);
    }
}
