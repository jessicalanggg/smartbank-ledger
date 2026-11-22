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
 * With signing keys configured ({@link SealSigner}), every new seal's hash is also
 * signed with an Ed25519 key the database never holds, so even a complete rewrite
 * of the chain by someone with full database access fails verification: it cannot
 * carry valid signatures, and the latest seal must always be signed. When signing
 * is first switched on, the sealer appends a seal covering no new transactions,
 * signed, so the history before it is covered too.
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
    private final SealSigner signer;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerSealer(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, SealSigner signer) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.signer = signer;
    }

    /** Without signing: only the hash chain is checked. */
    public LedgerSealer(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, SealSigner.disabled());
    }

    /** keyId and signature are null for seals made while signing was off. */
    public record Seal(long seq, String prevHash, String hash, int transactionCount, Instant sealedAt,
                       String keyId, String signature) { }

    /** Result of checking the whole chain. {@code firstBrokenSeal} is null when it is intact. */
    public record Verification(int sealsChecked, int transactionsChecked, Long firstBrokenSeal, String problem,
                               String headHash) {
        public boolean intact() { return firstBrokenSeal == null; }
    }

    /** At startup, so a newly configured signing key covers existing history at once. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void sealAtStartup() {
        run();
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
        // Nothing new to seal, unless signing was just switched on and the head is
        // unsigned: then an empty, signed seal vouches for everything before it.
        if (ids.isEmpty() && !(signer.enabled() && head != null && head.signature() == null)) return null;
        long seq = head == null ? 1 : head.seq() + 1;
        String prev = head == null ? GENESIS : head.hash();
        String hash = sealHash(seq, prev, canonical(ids));
        SealSigner.Signed signed = signer.enabled() ? signer.sign(seq, hash) : null;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO ledger_seals (seq, prev_hash, hash, transaction_count, sealed_at, key_id, signature)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                seq, prev, hash, ids.size(), Timestamp.from(now),
                signed == null ? null : signed.keyId(), signed == null ? null : signed.signature());
        jdbc.batchUpdate("INSERT INTO ledger_sealed_transactions (transaction_id, seal_seq) VALUES (?, ?)",
                ids.stream().map(id -> new Object[] { id, seq }).toList());
        return new Seal(seq, prev, hash, ids.size(), now,
                signed == null ? null : signed.keyId(), signed == null ? null : signed.signature());
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
            if (seal.signature() != null) {
                switch (signer.verify(seal.seq(), seal.hash(), seal.keyId(), seal.signature())) {
                    case INVALID -> {
                        return broken(checked, transactionsChecked, seal.seq(), "its signature does not match");
                    }
                    case UNKNOWN_KEY -> {
                        if (signer.enabled())
                            return broken(checked, transactionsChecked, seal.seq(), "it is signed with an unknown key");
                    }
                    case VALID -> { }
                }
            }
            prev = seal.hash();
            checked++;
            transactionsChecked += ids.size();
        }
        // A rewritten chain would end in a seal no one could sign: the head must be signed.
        // (Right after signing is first switched on, this holds until the sealer has
        // added its signed seal, which it does at startup.)
        if (signer.enabled() && !seals.isEmpty() && seals.get(seals.size() - 1).signature() == null)
            return broken(checked, transactionsChecked, seals.get(seals.size() - 1).seq(), "the latest seal is not signed");
        return new Verification(checked, transactionsChecked, null, null, prev);
    }

    private static Verification broken(int checked, int transactions, long seq, String problem) {
        return new Verification(checked, transactions, seq, problem, null);
    }

    private static Seal seal(ResultSet rs) throws SQLException {
        return new Seal(rs.getLong("seq"), rs.getString("prev_hash"), rs.getString("hash"),
                rs.getInt("transaction_count"), rs.getTimestamp("sealed_at").toInstant(),
                rs.getString("key_id"), rs.getString("signature"));
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
                       created_at, link_id, link_index, reverses_transaction_id, initiated_by,
                       counter_amount, counter_currency, fx_rate, fx_quote_id, agent_mandate_id
                FROM banking_transactions WHERE id = ANY (?)""",
                rs -> {
                    // FX fields are appended only when present, so transactions sealed before
                    // they existed keep the exact text, and hash, they were sealed with.
                    String fx = rs.getBigDecimal("counter_amount") == null ? "" : "|fx:" + String.join(",",
                            money(rs.getBigDecimal("counter_amount")), rs.getString("counter_currency"),
                            rs.getBigDecimal("fx_rate").toPlainString(), rs.getString("fx_quote_id"));
                    // Likewise the agent mandate, for transfers an agent made.
                    if (rs.getString("agent_mandate_id") != null) fx += "|agent:" + rs.getString("agent_mandate_id");
                    tx.put(rs.getObject("id", UUID.class), fx(String.join("|",
                            rs.getString("id"), rs.getString("transaction_type"), text(rs.getString("source_account_id")),
                            text(rs.getString("destination_account_id")), money(rs.getBigDecimal("amount")),
                            rs.getString("currency"), text(rs.getString("request_key")), micros(rs.getTimestamp("created_at")),
                            text(rs.getString("link_id")), text(rs.getString("link_index")),
                            text(rs.getString("reverses_transaction_id")), text(rs.getString("initiated_by"))), fx));
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
    private static String fx(String base, String fx) { return base + fx; }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private static String micros(Timestamp t) {
        Instant i = t.toInstant();
        return Long.toString(i.getEpochSecond() * 1_000_000 + i.getNano() / 1_000);
    }
}
