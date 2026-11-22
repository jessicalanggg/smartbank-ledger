-- Who started each transaction, and a tamper-evident hash chain over the ledger.
--
-- initiated_by is the user whose request created the transaction; NULL means the
-- system (the hold expiry job, migrations, opening balances).
--
-- Seals: a background job periodically takes the transactions not yet sealed,
-- hashes each one together with its ledger entries, and appends a seal whose hash
-- covers those transactions and the previous seal's hash. Changing, adding or
-- removing anything that is already sealed, even with the append-only triggers
-- switched off, then no longer matches the chain. Sealing runs after commit and
-- in the background, so posting is never serialized behind a single chain head.

ALTER TABLE banking_transactions ADD COLUMN initiated_by UUID REFERENCES app_users(id);

CREATE TABLE ledger_seals (
    seq BIGINT PRIMARY KEY CHECK (seq >= 1),
    prev_hash CHAR(64) NOT NULL,
    hash CHAR(64) NOT NULL UNIQUE,
    transaction_count INT NOT NULL CHECK (transaction_count > 0),
    sealed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE ledger_sealed_transactions (
    transaction_id UUID PRIMARY KEY REFERENCES banking_transactions(id),
    seal_seq BIGINT NOT NULL REFERENCES ledger_seals(seq)
);
CREATE INDEX idx_sealed_transactions_seal ON ledger_sealed_transactions(seal_seq);

CREATE TRIGGER ledger_seals_append_only
    BEFORE UPDATE OR DELETE ON ledger_seals
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER ledger_sealed_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_sealed_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
