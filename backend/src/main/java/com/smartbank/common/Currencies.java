package com.smartbank.common;

import com.smartbank.transaction.BankingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Supported currencies and their minor units (JPY has none, most have two). */
@Component
public class Currencies {
    private final JdbcTemplate jdbc;
    private final Map<String, Integer> minorUnits = new ConcurrentHashMap<>();

    public Currencies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int minorUnits(String currency) {
        return minorUnits.computeIfAbsent(currency, c -> {
            var units = jdbc.queryForList("SELECT minor_units FROM currencies WHERE code = ?", Integer.class, c);
            if (units.isEmpty()) throw new BankingException("Unsupported currency " + c);
            return units.get(0);
        });
    }

    /** Rejects amounts with more decimals than the currency has, such as 10.5 JPY. */
    public void requireValidAmount(BigDecimal amount, String currency) {
        int units = minorUnits(currency);
        if (amount.stripTrailingZeros().scale() > units)
            throw new BankingException(units == 0
                    ? currency + " amounts cannot have decimals"
                    : currency + " amounts can have at most " + units + " decimal places");
    }
}
