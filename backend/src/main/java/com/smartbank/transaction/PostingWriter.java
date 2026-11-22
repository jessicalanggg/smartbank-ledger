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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Writes postings with as few database round trips as possible. Both methods run
 * in the caller's transaction.
 *
 * - {@link #postAll} writes any number of transactions with their ledger entries,
 *   outbox events and webhook deliveries in one SQL statement. Foreign keys between them are
 *   checked at the end of the statement, and the ledger balance check at commit.
 * - {@link #applyBalanceChanges} sends all balance updates in one JDBC batch. The
 *   server runs them in the given order, so the caller's lock order is kept.
 */
@Component
public class PostingWriter {
    /**
     * One statement for every account change: deltas to the posted balance and to
     * the pending amounts. It applies only if the available balance afterwards
     * (balance - pending_debits) is still non-negative, so a debit or a new
     * reservation that the account cannot cover changes nothing and reports false.
     */
    private static final String APPLY_CHANGE = """
            UPDATE accounts SET balance = balance + ?, pending_debits = pending_debits + ?,
                pending_credits = pending_credits + ?, version = version + 1
            WHERE id = ? AND (balance + ?) - (pending_debits + ?) >= 0""";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public PostingWriter(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record BalanceChange(UUID accountId, BigDecimal delta) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionResponse post(BankingTransaction tx) {
        return postAll(List.of(tx)).get(0);
    }

    /**
     * Writes every transaction, all their ledger entries, one event each and the
     * webhook deliveries for those events, in a single statement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TransactionResponse> postAll(List<BankingTransaction> txs) {
        List<TransactionResponse> responses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        List<String> txValues = new ArrayList<>(), entryValues = new ArrayList<>(),
                eventValues = new ArrayList<>(), fanOutValues = new ArrayList<>();
        for (BankingTransaction tx : txs) {
            txValues.add("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            params.addAll(Arrays.asList(tx.getId(), tx.getTransactionType().name(), tx.getSourceAccountId(),
                    tx.getDestinationAccountId(), tx.getAmount(), tx.getCurrency(), tx.getRequestKey(),
                    Timestamp.from(tx.getCreatedAt()), tx.getLinkId(), tx.getLinkIndex(), tx.getReversesTransactionId(),
                    tx.getInitiatedBy(), tx.getCounterAmount(), tx.getCounterCurrency(), tx.getFxRate(), tx.getFxQuoteId()));
        }
        for (BankingTransaction tx : txs) {
            for (LedgerEntry e : BankingService.postings(tx)) {
                entryValues.add("(?, ?, ?, ?, ?, ?, ?)");
                params.addAll(Arrays.asList(e.getId(), e.getTransactionId(), e.getLedgerAccount().name(),
                        e.getAccountId(), e.getAmount(), e.getCurrency(), Timestamp.from(e.getCreatedAt())));
            }
        }
        List<OutboxWriter.PreparedEvent> events = new ArrayList<>();
        for (BankingTransaction tx : txs) {
            TransactionResponse response = TransactionResponse.from(tx);
            responses.add(response);
            var event = outbox.prepare(EventTypes.TRANSACTION_POSTED, tx.getId(), response,
                    tx.getSourceAccountId(), tx.getDestinationAccountId());
            events.add(event);
            eventValues.add("(?, ?, ?, string_to_array(?, ',')::uuid[], ?, ?)");
            params.addAll(List.of(event.eventParams()));
        }
        for (var event : events) {
            fanOutValues.add("(?::uuid, ?::varchar, ?::text, ?::timestamptz)");
            params.addAll(Arrays.asList(event.id(), event.type(), event.accounts(), event.at()));
        }
        jdbc.update("""
                WITH tx AS (
                    INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id,
                        amount, currency, request_key, created_at, link_id, link_index, reverses_transaction_id,
                        initiated_by, counter_amount, counter_currency, fx_rate, fx_quote_id)
                    VALUES %s),
                entries AS (
                    INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                    VALUES %s),
                events AS (
                    INSERT INTO outbox_events (id, event_type, aggregate_id, account_ids, payload, created_at)
                    VALUES %s)
                INSERT INTO webhook_deliveries (id, event_id, endpoint_id, status, attempts, next_attempt_at, created_at)
                SELECT gen_random_uuid(), ev.id, e.id, 'PENDING', 0, ev.at, ev.at
                FROM (VALUES %s) AS ev(id, type, accounts, at)
                JOIN webhook_endpoints e
                  ON e.active
                 AND (e.event_types IS NULL OR ev.type = ANY (e.event_types))
                 AND EXISTS (SELECT 1 FROM accounts a
                             WHERE a.id = ANY (string_to_array(ev.accounts, ',')::uuid[]) AND a.owner_user_id = e.user_id)"""
                .formatted(String.join(", ", txValues), String.join(", ", entryValues),
                        String.join(", ", eventValues), String.join(", ", fanOutValues)),
                params.toArray());
        return responses;
    }

    /** A change to an account's posted balance and pending amounts. */
    public record AccountChange(UUID accountId, BigDecimal balance, BigDecimal pendingDebits, BigDecimal pendingCredits) {
        public static AccountChange balance(UUID accountId, BigDecimal delta) {
            return new AccountChange(accountId, delta, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /** Applies the changes in order, in one round trip; returns whether each one applied. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean[] applyBalanceChanges(List<BalanceChange> changes) {
        return applyAccountChanges(changes.stream().map(c -> AccountChange.balance(c.accountId(), c.delta())).toList());
    }

    /**
     * Applies the changes in the given order, in one round trip; returns whether each
     * one applied. The server runs them in order, so the caller's lock order holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean[] applyAccountChanges(List<AccountChange> changes) {
        List<Object[]> batch = changes.stream()
                .map(c -> new Object[] { c.balance(), c.pendingDebits(), c.pendingCredits(), c.accountId(),
                        c.balance(), c.pendingDebits() })
                .toList();
        int[] counts = jdbc.batchUpdate(APPLY_CHANGE, batch);
        boolean[] applied = new boolean[counts.length];
        for (int i = 0; i < counts.length; i++) applied[i] = counts[i] == 1;
        return applied;
    }
}
