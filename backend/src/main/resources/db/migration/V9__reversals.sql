-- Reversals: mistakes are corrected with a new, opposite posting that points to the
-- original, never by editing history.
--
-- A REVERSAL moves money back along the original's path: a transfer's payee pays
-- the payer back; a deposit's money leaves the account to external clearing; a
-- withdrawal's money returns from external clearing. Several partial reversals
-- are allowed, but together they never exceed the original amount.

ALTER TABLE banking_transactions DROP CONSTRAINT banking_transactions_transaction_type_check;
ALTER TABLE banking_transactions ADD CONSTRAINT banking_transactions_transaction_type_check
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'OPENING_BALANCE', 'REVERSAL'));

ALTER TABLE banking_transactions
    ADD COLUMN reverses_transaction_id UUID REFERENCES banking_transactions(id),
    ADD CONSTRAINT reversal_points_to_original CHECK ((transaction_type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL));
CREATE INDEX idx_tx_reverses ON banking_transactions(reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL;

ALTER TABLE banking_transactions DROP CONSTRAINT valid_transaction_accounts;
ALTER TABLE banking_transactions ADD CONSTRAINT valid_transaction_accounts CHECK (
    (transaction_type IN ('DEPOSIT', 'OPENING_BALANCE') AND source_account_id IS NULL AND destination_account_id IS NOT NULL)
    OR (transaction_type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
    OR (transaction_type = 'TRANSFER' AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL AND source_account_id <> destination_account_id)
    OR (transaction_type = 'REVERSAL' AND (source_account_id IS NOT NULL OR destination_account_id IS NOT NULL)
        AND source_account_id IS DISTINCT FROM destination_account_id)
);

-- Checked at commit for every new reversal. It first locks the original, so two
-- concurrent reversals of one transaction are checked one after the other: the
-- second sees the first once it has committed, and cannot push the total past the
-- original amount.
CREATE FUNCTION check_reversal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    original banking_transactions%ROWTYPE;
    total NUMERIC;
BEGIN
    SELECT * INTO original FROM banking_transactions WHERE id = NEW.reverses_transaction_id FOR NO KEY UPDATE;
    IF original.transaction_type NOT IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL') THEN
        RAISE EXCEPTION 'Transaction % (%) cannot be reversed', original.id, original.transaction_type
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.source_account_id IS DISTINCT FROM original.destination_account_id
       OR NEW.destination_account_id IS DISTINCT FROM original.source_account_id
       OR NEW.currency <> original.currency THEN
        RAISE EXCEPTION 'Reversal % does not mirror transaction %', NEW.id, original.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    SELECT COALESCE(SUM(amount), 0) INTO total FROM banking_transactions WHERE reverses_transaction_id = original.id;
    IF total > original.amount THEN
        RAISE EXCEPTION 'Reversals of transaction % total %, more than its amount %', original.id, total, original.amount
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER reversals_within_original
    AFTER INSERT ON banking_transactions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.transaction_type = 'REVERSAL')
    EXECUTE FUNCTION check_reversal();
