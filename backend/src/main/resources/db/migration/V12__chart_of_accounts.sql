-- Chart of accounts.
--
-- Sign convention for ledger_entries.amount, which every posting rule since V4
-- already follows: a positive amount is a CREDIT, a negative amount a DEBIT. A
-- deposit credits the customer's account and debits the bank's cash; a transfer
-- debits the payer and credits the payee. Existing entries are therefore already
-- correct double-entry and are not rewritten (they are append-only); this
-- migration adds the typed accounts they post to.
--
-- Each account has a type and the normal balance that follows from it:
--   ASSET, EXPENSE                 debit-normal  (debits increase them)
--   LIABILITY, EQUITY, REVENUE     credit-normal (credits increase them)
-- A customer's money is a liability of the bank: the bank owes it to them.

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    account_type VARCHAR(10) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    currency VARCHAR(3) NOT NULL,
    -- Which ledger_entries.ledger_account kind posts here; NULL for a control account
    -- that only groups sub-accounts.
    posting_kind VARCHAR(30) CHECK (posting_kind IN ('CUSTOMER', 'EXTERNAL_CLEARING', 'OPENING_EQUITY', 'FEE_REVENUE')),
    customer_account_id UUID UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES ledger_accounts(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (code, currency),
    CONSTRAINT normal_balance_follows_type CHECK (
        (account_type IN ('ASSET', 'EXPENSE')) = (normal_balance = 'DEBIT')),
    CONSTRAINT customer_accounts_are_liabilities CHECK (
        (customer_account_id IS NOT NULL) = (posting_kind = 'CUSTOMER')
        AND (customer_account_id IS NULL OR account_type = 'LIABILITY'))
);
CREATE UNIQUE INDEX idx_ledger_accounts_system_kind ON ledger_accounts(posting_kind, currency)
    WHERE customer_account_id IS NULL AND posting_kind IS NOT NULL;

-- The system accounts for one currency, created when a currency is first used.
CREATE FUNCTION ensure_system_ledger_accounts(cur VARCHAR) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, posting_kind, created_at)
    VALUES (gen_random_uuid(), '1000', 'Cash and settlement', 'ASSET', 'DEBIT', cur, 'EXTERNAL_CLEARING', now()),
           (gen_random_uuid(), '2000', 'Customer deposits', 'LIABILITY', 'CREDIT', cur, NULL, now()),
           (gen_random_uuid(), '3900', 'Opening balance equity', 'EQUITY', 'CREDIT', cur, 'OPENING_EQUITY', now()),
           (gen_random_uuid(), '4000', 'Fee revenue', 'REVENUE', 'CREDIT', cur, 'FEE_REVENUE', now())
    ON CONFLICT (code, currency) DO NOTHING;
END $$;

-- Every customer account gets its own liability account under 2000 Customer deposits.
CREATE FUNCTION create_customer_ledger_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ensure_system_ledger_accounts(NEW.currency);
    INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, posting_kind,
                                 customer_account_id, parent_id, created_at)
    SELECT gen_random_uuid(), '2000-' || NEW.account_number, 'Customer deposits: ' || NEW.account_number,
           'LIABILITY', 'CREDIT', NEW.currency, 'CUSTOMER', NEW.id, p.id, now()
    FROM ledger_accounts p WHERE p.code = '2000' AND p.currency = NEW.currency;
    RETURN NEW;
END $$;

CREATE TRIGGER accounts_get_ledger_account
    AFTER INSERT ON accounts FOR EACH ROW EXECUTE FUNCTION create_customer_ledger_account();

-- Accounts that already exist.
SELECT ensure_system_ledger_accounts(c) FROM (SELECT DISTINCT currency AS c FROM accounts UNION SELECT 'CAD') currencies;
INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, posting_kind,
                             customer_account_id, parent_id, created_at)
SELECT gen_random_uuid(), '2000-' || a.account_number, 'Customer deposits: ' || a.account_number,
       'LIABILITY', 'CREDIT', a.currency, 'CUSTOMER', a.id, p.id, now()
FROM accounts a JOIN ledger_accounts p ON p.code = '2000' AND p.currency = a.currency;

-- Fees: a charge to a customer, credited to fee revenue.
ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_transaction_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OPENING_BALANCE', 'REVERSAL', 'FEE'));
ALTER TABLE banking_transactions DROP CONSTRAINT valid_transaction_accounts;
ALTER TABLE banking_transactions ADD CONSTRAINT valid_transaction_accounts CHECK (
    (transaction_type IN ('DEPOSIT', 'OPENING_BALANCE') AND source_account_id IS NULL AND destination_account_id IS NOT NULL)
    OR (transaction_type IN ('WITHDRAWAL', 'FEE') AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
    OR (transaction_type = 'TRANSFER' AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL AND source_account_id <> destination_account_id)
    OR (transaction_type = 'REVERSAL' AND (source_account_id IS NOT NULL OR destination_account_id IS NOT NULL)
        AND source_account_id IS DISTINCT FROM destination_account_id)
);
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_ledger_account_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_ledger_account_check
    CHECK (ledger_account IN ('CUSTOMER', 'EXTERNAL_CLEARING', 'OPENING_EQUITY', 'FEE_REVENUE'));

-- Every ledger entry as a journal line on its chart account, with debit and credit columns.
CREATE VIEW journal_lines AS
SELECT e.id AS entry_id, e.transaction_id, la.id AS ledger_account_id, la.code, la.name, la.account_type,
       la.normal_balance, la.parent_id,
       CASE WHEN e.amount < 0 THEN -e.amount ELSE 0 END AS debit,
       CASE WHEN e.amount > 0 THEN e.amount ELSE 0 END AS credit,
       e.currency, e.created_at
FROM ledger_entries e
JOIN ledger_accounts la ON la.customer_account_id = e.account_id
WHERE e.ledger_account = 'CUSTOMER'
UNION ALL
SELECT e.id, e.transaction_id, la.id, la.code, la.name, la.account_type, la.normal_balance, la.parent_id,
       CASE WHEN e.amount < 0 THEN -e.amount ELSE 0 END,
       CASE WHEN e.amount > 0 THEN e.amount ELSE 0 END,
       e.currency, e.created_at
FROM ledger_entries e
JOIN ledger_accounts la ON la.posting_kind = e.ledger_account AND la.currency = e.currency
                       AND la.customer_account_id IS NULL
WHERE e.ledger_account <> 'CUSTOMER';
