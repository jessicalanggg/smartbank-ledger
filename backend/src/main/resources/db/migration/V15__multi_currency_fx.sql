-- Multi-currency and foreign exchange.
--
-- Currencies have minor units (JPY has none, most have two); amounts may not use
-- more decimals than their currency has.
--
-- An FX transfer is one transaction with entries in two currencies, and each
-- currency balances on its own through that currency's FX position account:
--   sell side (e.g. CAD):  DR payer 100.00 CAD      CR 3500 FX position CAD 100.00
--   buy side  (e.g. USD):  DR 3500 FX position USD 73.00   CR payee 73.00 USD
-- The FX position accounts hold the bank's net position in each currency from
-- conversions. Their value in one reporting currency is the bank's FX gain or loss;
-- revaluing them is out of scope here.
--
-- Customers convert at a quoted rate: a quote locks a rate and both amounts for a
-- short time and can be used once.

CREATE TABLE currencies (
    code VARCHAR(3) PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    name VARCHAR(60) NOT NULL,
    minor_units SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 2)
);
INSERT INTO currencies (code, name, minor_units) VALUES
    ('CAD', 'Canadian dollar', 2), ('USD', 'US dollar', 2), ('EUR', 'Euro', 2),
    ('GBP', 'Pound sterling', 2), ('JPY', 'Japanese yen', 0);
INSERT INTO currencies (code, name, minor_units)
SELECT DISTINCT currency, currency, 2 FROM accounts ON CONFLICT (code) DO NOTHING;
ALTER TABLE accounts ADD CONSTRAINT accounts_currency_known FOREIGN KEY (currency) REFERENCES currencies(code);

-- FX position accounts in the chart, one per currency.
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_posting_kind_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_posting_kind_check
    CHECK (posting_kind IN ('CUSTOMER', 'EXTERNAL_CLEARING', 'OPENING_EQUITY', 'FEE_REVENUE', 'FX_POSITION'));
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_ledger_account_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_ledger_account_check
    CHECK (ledger_account IN ('CUSTOMER', 'EXTERNAL_CLEARING', 'OPENING_EQUITY', 'FEE_REVENUE', 'FX_POSITION'));

CREATE OR REPLACE FUNCTION ensure_system_ledger_accounts(cur VARCHAR) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, posting_kind, created_at)
    VALUES (gen_random_uuid(), '1000', 'Cash and settlement', 'ASSET', 'DEBIT', cur, 'EXTERNAL_CLEARING', now()),
           (gen_random_uuid(), '2000', 'Customer deposits', 'LIABILITY', 'CREDIT', cur, NULL, now()),
           (gen_random_uuid(), '3500', 'FX position', 'EQUITY', 'CREDIT', cur, 'FX_POSITION', now()),
           (gen_random_uuid(), '3900', 'Opening balance equity', 'EQUITY', 'CREDIT', cur, 'OPENING_EQUITY', now()),
           (gen_random_uuid(), '4000', 'Fee revenue', 'REVENUE', 'CREDIT', cur, 'FEE_REVENUE', now())
    ON CONFLICT (code, currency) DO NOTHING;
END $$;
SELECT ensure_system_ledger_accounts(code) FROM currencies;

-- FX transfers: amount and currency are the sell side; the counter fields the buy side.
ALTER TABLE banking_transactions
    ADD COLUMN counter_amount NUMERIC(19,2) CHECK (counter_amount > 0),
    ADD COLUMN counter_currency VARCHAR(3) REFERENCES currencies(code),
    ADD COLUMN fx_rate NUMERIC(20,10) CHECK (fx_rate > 0),
    ADD COLUMN fx_quote_id UUID UNIQUE;
ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_transaction_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OPENING_BALANCE', 'REVERSAL', 'FEE', 'FX_TRANSFER'));
ALTER TABLE banking_transactions DROP CONSTRAINT valid_transaction_accounts;
ALTER TABLE banking_transactions ADD CONSTRAINT valid_transaction_accounts CHECK (
    (transaction_type IN ('DEPOSIT', 'OPENING_BALANCE') AND source_account_id IS NULL AND destination_account_id IS NOT NULL)
    OR (transaction_type IN ('WITHDRAWAL', 'FEE') AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
    OR (transaction_type IN ('TRANSFER', 'FX_TRANSFER') AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
        AND source_account_id <> destination_account_id)
    OR (transaction_type = 'REVERSAL' AND (source_account_id IS NOT NULL OR destination_account_id IS NOT NULL)
        AND source_account_id IS DISTINCT FROM destination_account_id)
);
ALTER TABLE banking_transactions ADD CONSTRAINT fx_fields_only_on_fx CHECK (
    (transaction_type = 'FX_TRANSFER') = (counter_amount IS NOT NULL AND counter_currency IS NOT NULL
                                         AND fx_rate IS NOT NULL AND fx_quote_id IS NOT NULL)
    AND (counter_currency IS NULL OR counter_currency <> currency)
);

-- Balance per currency: every transaction's entries sum to zero within each currency,
-- in exactly one currency, or two for an FX transfer.
CREATE OR REPLACE FUNCTION check_transaction_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    tx_id UUID;
    tx_type VARCHAR;
    entry_count INT;
    currencies INT;
    expected_currencies INT;
    unbalanced INT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        tx_id := NEW.transaction_id;
    ELSE
        tx_id := NEW.id;
    END IF;
    SELECT transaction_type INTO tx_type FROM banking_transactions WHERE id = tx_id;
    SELECT COUNT(*), COUNT(DISTINCT currency) INTO entry_count, currencies
      FROM ledger_entries WHERE transaction_id = tx_id;
    SELECT COUNT(*) INTO unbalanced FROM (
        SELECT currency FROM ledger_entries WHERE transaction_id = tx_id GROUP BY currency HAVING SUM(amount) <> 0) u;
    -- (Computed first: in PL/pgSQL an IF condition ends at its first THEN, even inside CASE.)
    expected_currencies := CASE WHEN tx_type = 'FX_TRANSFER' THEN 2 ELSE 1 END;
    IF entry_count < 2 OR unbalanced > 0 OR currencies <> expected_currencies THEN
        RAISE EXCEPTION 'Transaction % is not balanced: % entries, % currencies, % unbalanced',
            tx_id, entry_count, currencies, unbalanced
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END $$;

-- Rates: 1 unit of base buys `rate` units of quote. Each direction is set separately,
-- since a bank's buy and sell rates differ. Append-only: the latest row is current.
CREATE TABLE fx_rates (
    id UUID PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    quote_currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    rate NUMERIC(20,10) NOT NULL CHECK (rate > 0),
    set_by UUID REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (base_currency <> quote_currency)
);
CREATE INDEX idx_fx_rates_pair ON fx_rates(base_currency, quote_currency, created_at DESC);
CREATE TRIGGER fx_rates_append_only BEFORE UPDATE OR DELETE ON fx_rates
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TABLE fx_quotes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    from_account_id UUID NOT NULL REFERENCES accounts(id),
    to_account_id UUID NOT NULL REFERENCES accounts(id),
    sell_amount NUMERIC(19,2) NOT NULL CHECK (sell_amount > 0),
    sell_currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    buy_amount NUMERIC(19,2) NOT NULL CHECK (buy_amount > 0),
    buy_currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    rate NUMERIC(20,10) NOT NULL CHECK (rate > 0),
    rate_id UUID NOT NULL REFERENCES fx_rates(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_by_transaction_id UUID UNIQUE REFERENCES banking_transactions(id),
    CHECK (sell_currency <> buy_currency),
    CHECK (expires_at > created_at)
);
-- A quote can only change once: from unused to used.
CREATE FUNCTION enforce_quote_use() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.used_by_transaction_id IS NOT NULL OR NEW.used_by_transaction_id IS NULL
       OR (NEW.id, NEW.user_id, NEW.from_account_id, NEW.to_account_id, NEW.sell_amount, NEW.sell_currency,
           NEW.buy_amount, NEW.buy_currency, NEW.rate, NEW.rate_id, NEW.created_at, NEW.expires_at)
          IS DISTINCT FROM
          (OLD.id, OLD.user_id, OLD.from_account_id, OLD.to_account_id, OLD.sell_amount, OLD.sell_currency,
           OLD.buy_amount, OLD.buy_currency, OLD.rate, OLD.rate_id, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'FX quote % can only be marked used once', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fx_quotes_use_once BEFORE UPDATE OR DELETE ON fx_quotes
    FOR EACH ROW EXECUTE FUNCTION enforce_quote_use();

-- Demonstration data: a USD account for demo customer A (ownership is assigned by the
-- local demo bootstrap) and demonstration rates.
INSERT INTO accounts (id, account_number, owner_name, account_type, balance, currency)
VALUES ('33333333-3333-4333-8333-333333333333', 'DEMO-10003', 'Demo Customer A', 'CHECKING', 500.00, 'USD');
WITH t AS (
    INSERT INTO banking_transactions (id, transaction_type, destination_account_id, amount, currency, created_at)
    VALUES ('33333333-0000-4000-8000-000000000003', 'OPENING_BALANCE', '33333333-3333-4333-8333-333333333333',
            500.00, 'USD', now() - INTERVAL '1 second'))
INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
VALUES (gen_random_uuid(), '33333333-0000-4000-8000-000000000003', 'CUSTOMER', '33333333-3333-4333-8333-333333333333',
        500.00, 'USD', now() - INTERVAL '1 second'),
       (gen_random_uuid(), '33333333-0000-4000-8000-000000000003', 'OPENING_EQUITY', NULL,
        -500.00, 'USD', now() - INTERVAL '1 second');
INSERT INTO fx_rates (id, base_currency, quote_currency, rate, created_at) VALUES
    (gen_random_uuid(), 'CAD', 'USD', 0.7300000000, now()),
    (gen_random_uuid(), 'USD', 'CAD', 1.3600000000, now());
