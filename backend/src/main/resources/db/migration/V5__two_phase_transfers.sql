-- Two-phase transfers (holds / authorizations).
--
-- A hold reserves money without moving it: it raises the payer's pending debits
-- and the payee's pending credits. Posting a hold (in full or in part) creates an
-- ordinary TRANSFER with ledger entries and releases the reservation; voiding or
-- expiring it just releases the reservation. Holds are not ledger entries, because
-- no money has moved; the ledger records only posted transfers.
--
-- Available balance = balance - pending_debits, and it may never go negative.

ALTER TABLE accounts
    ADD COLUMN pending_debits NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (pending_debits >= 0),
    ADD COLUMN pending_credits NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (pending_credits >= 0),
    ADD CONSTRAINT available_balance_non_negative CHECK (balance - pending_debits >= 0);

CREATE TABLE holds (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'VOIDED', 'EXPIRED')),
    posted_amount NUMERIC(19,2),
    posted_transaction_id UUID UNIQUE REFERENCES banking_transactions(id),
    request_key VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT hold_accounts_differ CHECK (source_account_id <> destination_account_id),
    CONSTRAINT hold_expires_after_creation CHECK (expires_at > created_at),
    CONSTRAINT hold_posted_fields CHECK (
        (status = 'POSTED') = (posted_amount IS NOT NULL AND posted_transaction_id IS NOT NULL)
    ),
    CONSTRAINT hold_posted_amount_within_hold CHECK (posted_amount IS NULL OR (posted_amount > 0 AND posted_amount <= amount)),
    CONSTRAINT hold_resolved_iff_final CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);
CREATE INDEX idx_holds_source ON holds(source_account_id, created_at DESC);
CREATE INDEX idx_holds_destination ON holds(destination_account_id, created_at DESC);
CREATE INDEX idx_holds_pending_expiry ON holds(expires_at) WHERE status = 'PENDING';

-- A hold changes state exactly once, from PENDING to a final state, and nothing
-- else about it may change. Holds are never deleted.
CREATE FUNCTION enforce_hold_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'holds cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING'
       OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.source_account_id IS DISTINCT FROM OLD.source_account_id
       OR NEW.destination_account_id IS DISTINCT FROM OLD.destination_account_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.request_key IS DISTINCT FROM OLD.request_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'hold % cannot change from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER holds_transition_once
    BEFORE UPDATE OR DELETE ON holds
    FOR EACH ROW EXECUTE FUNCTION enforce_hold_transition();
