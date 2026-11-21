package com.smartbank.transaction;

import com.smartbank.events.EventTypes;
import com.smartbank.events.OutboxWriter;
import com.smartbank.ledger.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes postings with as few database round trips as possible. Both methods run
 * in the caller's transaction.
 *
 * - {@link #post} writes the transaction row, its ledger entries, its outbox event
 *   and the webhook deliveries in one SQL statement. Foreign keys between them are
 *   checked at the end of the statement, and the ledger balance check at commit.
 * - {@link #applyBalanceChanges} sends all balance updates in one JDBC batch. The
 *   server runs them in the given order, so the caller's lock order is kept.
 */
@Component
public class PostingWriter {
    private static final String INSERT_TRANSACTION = """
            INSERT INTO banking_transactions
                (id, transaction_type, source_account_id, destination_account_id, amount, currency, request_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String ENTRY_VALUES = "(?, ?, ?, ?, ?, ?, ?)";

    /**
     * One statement for debits and credits: a negative delta is a debit and only
     * applies if the available balance covers it; a positive delta always applies.
     */
    private static final String APPLY_DELTA = """
            UPDATE accounts SET balance = balance + ?, version = version + 1
            WHERE id = ? AND balance - pending_debits + ? >= 0""";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public PostingWriter(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record BalanceChange(UUID accountId, BigDecimal delta) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionResponse post(BankingTransaction tx) {
        TransactionResponse response = TransactionResponse.from(tx);
        List<LedgerEntry> entries = BankingService.postings(tx);
        var event = outbox.prepare(EventTypes.TRANSACTION_POSTED, tx.getId(), response,
                tx.getSourceAccountId(), tx.getDestinationAccountId());

        List<Object> params = new ArrayList<>(List.of(tx.getId(), tx.getTransactionType().name()));
        params.add(tx.getSourceAccountId());
        params.add(tx.getDestinationAccountId());
        params.addAll(List.of(tx.getAmount(), tx.getCurrency()));
        params.add(tx.getRequestKey());
        params.add(Timestamp.from(tx.getCreatedAt()));
        List<String> values = new ArrayList<>();
        for (LedgerEntry e : entries) {
            values.add(ENTRY_VALUES);
            params.add(e.getId());
            params.add(e.getTransactionId());
            params.add(e.getLedgerAccount().name());
            params.add(e.getAccountId());
            params.add(e.getAmount());
            params.add(e.getCurrency());
            params.add(Timestamp.from(e.getCreatedAt()));
        }
        params.addAll(List.of(event.eventParams()));
        params.addAll(List.of(event.fanOutParams()));

        jdbc.update("WITH tx AS (" + INSERT_TRANSACTION + "),\n"
                + "entries AS (INSERT INTO ledger_entries "
                + "(id, transaction_id, ledger_account, account_id, amount, currency, created_at) VALUES "
                + String.join(", ", values) + "),\n"
                + "event AS (" + OutboxWriter.INSERT_EVENT + ")\n"
                + OutboxWriter.FAN_OUT, params.toArray());
        return response;
    }

    /** Applies the changes in order, in one round trip; returns whether each one applied. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean[] applyBalanceChanges(List<BalanceChange> changes) {
        List<Object[]> batch = changes.stream()
                .map(c -> new Object[] { c.delta(), c.accountId(), c.delta() })
                .toList();
        int[] counts = jdbc.batchUpdate(APPLY_DELTA, batch);
        boolean[] applied = new boolean[counts.length];
        for (int i = 0; i < counts.length; i++) applied[i] = counts[i] == 1;
        return applied;
    }
}
