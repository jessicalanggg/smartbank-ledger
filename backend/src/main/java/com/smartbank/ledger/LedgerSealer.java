package com.smartbank.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Tamper evidence for the ledger: a hash chain of seals over committed transactions.
 *
 * Every few seconds the sealer takes the transactions not yet sealed, writes each
 * one (with all its ledger entries) in a fixed text form, and appends a seal:
 *
 *   hash(seal n) = SHA-256(hash(seal n-1), n, the sealed transactions in order)
 *
 * Changing, adding or removing anything already sealed, even with the database's
 * append-only triggers switched off, makes the recomputed hash of that seal differ,
 * and a rewritten seal no longer matches the next seal's link. Rewriting the whole
 * chain from the tampered point is caught by comparing the head against a copy kept
 * outside the database: the sealer logs every new head, and administrators can read
 * it from the API to record elsewhere.
 *
 * Sealing happens after commit and in the background, so posting never waits on a
 * single chain head. A PostgreSQL advisory lock lets only one app instance seal at
 * a time. Transactions are only final, and only sealable, once committed; anything
 * committed after a seal is picked up by the next one.
 */
@Component
public class LedgerSealer {
    private static final Logger log = LoggerFactory.getLogger(LedgerSealer.class);
    static final String GENESIS = "0".repeat(64);
    private static final long ADVISORY_LOCK = 0x534d415254L;  // "SMART"
    private static final int MAX_PER_SEAL = 2_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public LedgerSealer(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Seal(long seq, String prevHash, String hash, int transactionCount, Instant sealedAt) { }

    /** Result of checking the whole chain. {@code firstBrokenSeal} is null when it is intact. */
    public record Verification(int sealsChecked, int transactionsChecked, Long firstBrokenSeal, String problem,
                               String headHash) {
        public boolean intact() { return firstBrokenSeal == null; }
    }

    @Scheduled(fixedDelayString = "${smartbank.ledger.seal-interval:PT5S}")
    public void run() {
        try {
            sealPending();
        } catch (RuntimeException e) {
            log.warn("Sealing the ledger failed", e);
        }
    }

    /** Seals every committed, unsealed transaction; returns the new seals. */
    public List<Seal> sealPending() {
        List<Seal> sealed = new ArrayList<>();
        while (true) {
            Seal seal = transactions.execute(status -> sealOnce());
            if (seal == null) return sealed;
            sealed.add(seal);
            log.info("Ledger seal {} covers {} transactions; chain head {}", seal.seq(), seal.transactionCount(), seal.hash());
            if (seal.transactionCount() < MAX_PER_SEAL) return sealed;
        }
    }

    private Seal sealOnce() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ADVISORY_LOCK);
        Seal head = head();
        List<UUID> ids = jdbc.queryForList("""
                SELECT t.id FROM banking_transactions t
                WHERE NOT EXISTS (SELECT 1 FROM ledger_sealed_transactions s WHERE s.transaction_id = t.id)
                ORDER BY t.created_at, t.id LIMIT ?""", UUID.class, MAX_PER_SEAL);
        if (ids.isEmpty()) return null;
        long seq = head == null ? 1 : head.seq() + 1;
        String prev = head == null ? GENESIS : head.hash();
        String hash = sealHash(seq, prev, canonical(ids));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO ledger_seals (seq, prev_hash, hash, transaction_count, sealed_at) VALUES (?, ?, ?, ?, ?)",
                seq, prev, hash, ids.size(), Timestamp.from(now));
        jdbc.batchUpdate("INSERT INTO ledger_sealed_transactions (transaction_id, seal_seq) VALUES (?, ?)",
                ids.stream().map(id -> new Object[] { id, seq }).toList());
        return new Seal(seq, prev, hash, ids.size(), now);
    }

    /** The latest seal, or null if nothing is sealed yet. */
    public Seal head() {
        List<Seal> rows = jdbc.query("SELECT * FROM ledger_seals ORDER BY seq DESC LIMIT 1", (rs, n) -> seal(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Recomputes every seal from what the database holds now, oldest first. */
    public Verification verify() {
        List<Seal> seals = jdbc.query("SELECT * FROM ledger_seals ORDER BY seq", (rs, n) -> seal(rs));
        String prev = GENESIS;
        int checked = 0, transactionsChecked = 0;
        for (Seal seal : seals) {
            if (seal.seq() != checked + 1)
                return broken(checked, transactionsChecked, seal.seq(), "seal " + (checked + 1) + " is missing");
            if (!seal.prevHash().equals(prev))
                return broken(checked, transactionsChecked, seal.seq(), "does not link to the previous seal");
            List<UUID> ids = jdbc.queryForList("""
                    SELECT s.transaction_id FROM ledger_sealed_transactions s
                    LEFT JOIN banking_transactions t ON t.id = s.transaction_id
                    WHERE s.seal_seq = ? ORDER BY t.created_at, s.transaction_id""", UUID.class, seal.seq());
            if (ids.size() != seal.transactionCount())
                return broken(checked, transactionsChecked, seal.seq(), "sealed transactions were added or removed");
            String recomputed;
            try {
                recomputed = sealHash(seal.seq(), seal.prevHash(), canonical(ids));
            } catch (IllegalStateException e) {
                return broken(checked, transactionsChecked, seal.seq(), e.getMessage());
            }
            if (!recomputed.equals(seal.hash()))
                return broken(checked, transactionsChecked, seal.seq(), "sealed transactions or entries were changed");
            prev = seal.hash();
            checked++;
            transactionsChecked += ids.size();
        }
        return new Verification(checked, transactionsChecked, null, null, prev);
    }

    private static Verification broken(int checked, int transactions, long seq, String problem) {
        return new Verification(checked, transactions, seq, problem, null);
    }

    private static Seal seal(ResultSet rs) throws SQLException {
        return new Seal(rs.getLong("seq"), rs.getString("prev_hash"), rs.getString("hash"),
                rs.getInt("transaction_count"), rs.getTimestamp("sealed_at").toInstant());
    }

    /**
     * Each transaction and its entries as one fixed line of text, in the given order.
     * Amounts are normalized to two decimals and times to microseconds, so the same
     * data always produces the same text.
     */
    List<String> canonical(List<UUID> ids) {
        Map<UUID, String> tx = new HashMap<>();
        jdbc.query("""
                SELECT id, transaction_type, source_account_id, destination_account_id, amount, currency, request_key,
                       created_at, link_id, link_index, reverses_transaction_id, initiated_by
                FROM banking_transactions WHERE id = ANY (?)""",
                rs -> {
                    tx.put(rs.getObject("id", UUID.class), String.join("|",
                            rs.getString("id"), rs.getString("transaction_type"), text(rs.getString("source_account_id")),
                            text(rs.getString("destination_account_id")), money(rs.getBigDecimal("amount")),
                            rs.getString("currency"), text(rs.getString("request_key")), micros(rs.getTimestamp("created_at")),
                            text(rs.getString("link_id")), text(rs.getString("link_index")),
                            text(rs.getString("reverses_transaction_id")), text(rs.getString("initiated_by"))));
                },
                (Object) ids.toArray(new UUID[0]));
        Map<UUID, List<String>> entries = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, id, ledger_account, account_id, amount, currency, created_at
                FROM ledger_entries WHERE transaction_id = ANY (?) ORDER BY id""",
                rs -> {
                    entries.computeIfAbsent(rs.getObject("transaction_id", UUID.class), k -> new ArrayList<>())
                            .add(String.join(",", rs.getString("id"), rs.getString("ledger_account"),
                                    text(rs.getString("account_id")), money(rs.getBigDecimal("amount")),
                                    rs.getString("currency"), micros(rs.getTimestamp("created_at"))));
                },
                (Object) ids.toArray(new UUID[0]));
        List<String> lines = new ArrayList<>();
        for (UUID id : ids) {
            String row = tx.get(id);
            if (row == null) throw new IllegalStateException("sealed transaction " + id + " no longer exists");
            lines.add(row + "#" + String.join(";", entries.getOrDefault(id, List.of())));
        }
        return lines;
    }

    static String sealHash(long seq, String prev, List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((prev + "\n" + seq + "\n").getBytes(StandardCharsets.UTF_8));
            for (String line : lines) sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String value) { return value == null ? "-" : value; }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private static String micros(Timestamp t) {
        Instant i = t.toInstant();
        return Long.toString(i.getEpochSecond() * 1_000_000 + i.getNano() / 1_000);
    }
}
