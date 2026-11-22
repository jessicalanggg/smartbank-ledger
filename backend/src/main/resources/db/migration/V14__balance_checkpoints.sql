-- Balance checkpoints: each ledger account's cumulative debits and credits up to a
-- moment, so a balance at any time is the latest checkpoint before it plus the few
-- entries since, instead of a sum over the account's whole history.
--
-- A checkpoint at moment T covers entries with created_at < T. It is only taken once
-- no transaction that could still add an entry before T can be open: T lies at least
-- a safety horizon in the past, and the application limits transactions to a much
-- shorter time (transaction_timeout). Reconciliation re-checks every checkpoint
-- against the entries, so one that missed a late entry would be reported.
--
-- Checkpoints are sparse: an account gets one at a boundary only if it had entries
-- since its previous checkpoint. They are append-only.

CREATE TABLE ledger_checkpoints (
    ledger_account_id UUID NOT NULL REFERENCES ledger_accounts(id) ON DELETE CASCADE,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    debits NUMERIC(21,2) NOT NULL CHECK (debits >= 0),
    credits NUMERIC(21,2) NOT NULL CHECK (credits >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (ledger_account_id, as_of)
);

-- Boundaries already checkpointed, so the job never repeats or half-finishes one.
CREATE TABLE ledger_checkpoint_runs (
    as_of TIMESTAMP WITH TIME ZONE PRIMARY KEY,
    accounts INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TRIGGER ledger_checkpoints_append_only
    BEFORE UPDATE OR DELETE ON ledger_checkpoints
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER ledger_checkpoint_runs_append_only
    BEFORE UPDATE OR DELETE ON ledger_checkpoint_runs
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Time-ranged reads of one account's entries: statements and balances since a checkpoint.
CREATE INDEX idx_ledger_customer_time ON ledger_entries(account_id, created_at) WHERE account_id IS NOT NULL;
CREATE INDEX idx_ledger_system_time ON ledger_entries(ledger_account, currency, created_at) WHERE account_id IS NULL;
