package com.smartbank.common;

import java.util.Comparator;
import java.util.UUID;

/**
 * The one lock order for accounts, matching PostgreSQL's uuid ordering (unsigned,
 * byte by byte). Java's UUID.compareTo compares the halves as signed longs and so
 * disagrees with PostgreSQL for about half of all pairs. Every code path that
 * locks more than one account, in Java or with SQL ORDER BY id, must use this
 * order, or two paths could lock the same accounts in opposite orders and
 * deadlock.
 */
public final class UuidOrder {
    private UuidOrder() { }

    public static final Comparator<UUID> COMPARATOR = (a, b) -> {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    public static boolean before(UUID a, UUID b) {
        return COMPARATOR.compare(a, b) < 0;
    }
}
