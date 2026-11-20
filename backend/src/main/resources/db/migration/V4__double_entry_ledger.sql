-- Double-entry ledger.
--
-- Every banking transaction posts two or more ledger entries whose signed amounts
-- sum to zero. A positive amount increases the ledger account, a negative amount
-- decreases it. Customer accounts are ledger accounts of kind CUSTOMER; money
-- entering or leaving the bank through deposits and withdrawals is posted against
-- EXTERNAL_CLEARING, and balances that existed before this migration are posted
-- against OPENING_EQUITY. accounts.balance remains as a cached balance for
-- locking and overdraft checks; the ledger is the record it must reconcile with.

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES banking_transactions(id),
    ledger_account VARCHAR(30) NOT NULL
        CHECK (ledger_account IN ('CUSTOMER', 'EXTERNAL_CLEARING', 'OPENING_EQUITY')),
    account_id UUID REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT customer_entries_name_an_account CHECK (
        (ledger_account = 'CUSTOMER') = (account_id IS NOT NULL)
    )
);
CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_account ON ledger_entries(account_id) WHERE account_id IS NOT NULL;

-- Allow opening-balance transactions, which credit one account from equity.
ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_transaction_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OPENING_BALANCE'));
ALTER TABLE banking_transactions DROP CONSTRAINT valid_transaction_accounts;
ALTER TABLE banking_transactions ADD CONSTRAINT valid_transaction_accounts CHECK (
    (transaction_type IN ('DEPOSIT', 'OPENING_BALANCE') AND source_account_id IS NULL AND destination_account_id IS NOT NULL)
    OR (transaction_type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
    OR (transaction_type = 'TRANSFER' AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL AND source_account_id <> destination_account_id)
);

-- Backfill entries for transactions recorded before the ledger existed.
INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
SELECT gen_random_uuid(), t.id, e.ledger_account, e.account_id, e.amount, t.currency, t.created_at
FROM banking_transactions t
CROSS JOIN LATERAL (VALUES
    (CASE WHEN t.transaction_type = 'DEPOSIT' THEN 'EXTERNAL_CLEARING' ELSE 'CUSTOMER' END,
     t.source_account_id, -t.amount),
    (CASE WHEN t.transaction_type = 'WITHDRAWAL' THEN 'EXTERNAL_CLEARING' ELSE 'CUSTOMER' END,
     t.destination_account_id, t.amount)
) AS e(ledger_account, account_id, amount);

-- Whatever part of each balance the transaction history does not explain was there
-- before any transactions (seed data). Record it as an opening balance dated just
-- before the account's first transaction, so the ledger explains every balance.
-- A negative remainder would fail the amount > 0 check and stop the migration,
-- which is intended: it means the existing data is already inconsistent.
CREATE TEMPORARY TABLE opening_balances ON COMMIT DROP AS
SELECT a.id AS account_id,
       gen_random_uuid() AS transaction_id,
       a.balance - COALESCE(SUM(l.amount), 0) AS amount,
       a.currency,
       COALESCE(MIN(l.created_at), now()) - INTERVAL '1 second' AS created_at
FROM accounts a
LEFT JOIN ledger_entries l ON l.account_id = a.id
GROUP BY a.id, a.balance, a.currency
HAVING a.balance - COALESCE(SUM(l.amount), 0) <> 0;

INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id, amount, currency, request_key, created_at)
SELECT transaction_id, 'OPENING_BALANCE', NULL, account_id, amount, currency, NULL, created_at
FROM opening_balances;

INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
SELECT gen_random_uuid(), transaction_id, 'CUSTOMER', account_id, amount, currency, created_at FROM opening_balances
UNION ALL
SELECT gen_random_uuid(), transaction_id, 'OPENING_EQUITY', NULL, -amount, currency, created_at FROM opening_balances;

-- The ledger and the transaction log are append-only. TRUNCATE is not blocked; it
-- is an administrative operation that the application never issues.
CREATE FUNCTION reject_modification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER banking_transactions_append_only
    BEFORE UPDATE OR DELETE ON banking_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Checked at COMMIT, once all entries of the transaction have been written: the
-- entries balance to zero, share one currency, and there are at least two.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    tx_id UUID;
    entry_count INT;
    total NUMERIC;
    currencies INT;
BEGIN
    -- NEW has a different row type per table, so read the field in separate branches.
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        tx_id := NEW.transaction_id;
    ELSE
        tx_id := NEW.id;
    END IF;
    SELECT COUNT(*), COALESCE(SUM(amount), 0), COUNT(DISTINCT currency)
      INTO entry_count, total, currencies
      FROM ledger_entries WHERE transaction_id = tx_id;
    IF entry_count < 2 OR total <> 0 OR currencies <> 1 THEN
        RAISE EXCEPTION 'Transaction % is not balanced: % entries, sum %, % currencies',
            tx_id, entry_count, total, currencies
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();
CREATE CONSTRAINT TRIGGER banking_transactions_have_entries
    AFTER INSERT ON banking_transactions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();
