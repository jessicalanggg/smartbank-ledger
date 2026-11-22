-- Agent spending mandates: a customer lets an AI agent pay from one account, within
-- limits the ledger enforces.
--
-- - A mandate gives an agent an API key tied to one account, with a per-payment
--   limit, a daily limit, an optional list of allowed payees and an expiry. It can be
--   revoked; nothing else about it ever changes.
-- - A payment within the limits posts at once. Its amount is added to the day's usage
--   in the same transaction as the transfer, by one conditional statement, so
--   simultaneous payments can never together exceed the daily limit.
-- - A payment over a limit either is rejected or, if the mandate asks, becomes a hold
--   on the account that waits for the owner to approve or decline it.
-- - Every payment the agent attempts is recorded, including rejected ones, and every
--   transfer an agent caused names its mandate in the ledger (and so in the seals).

CREATE TABLE agent_mandates (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    name VARCHAR(100) NOT NULL,
    -- SHA-256 of the API key; the key itself is shown once and never stored.
    key_hash CHAR(64) NOT NULL UNIQUE,
    key_prefix VARCHAR(20) NOT NULL,
    currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    per_payment_limit NUMERIC(19,2) NOT NULL CHECK (per_payment_limit > 0),
    daily_limit NUMERIC(19,2) NOT NULL,
    ask_above_limit BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT mandate_daily_covers_payment CHECK (daily_limit >= per_payment_limit),
    CONSTRAINT mandate_expires_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX idx_agent_mandates_user ON agent_mandates(user_id, created_at DESC);

-- A mandate can only change once: from active to revoked.
CREATE FUNCTION enforce_mandate_revocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
       OR (NEW.id, NEW.user_id, NEW.account_id, NEW.name, NEW.key_hash, NEW.key_prefix, NEW.currency,
           NEW.per_payment_limit, NEW.daily_limit, NEW.ask_above_limit, NEW.created_at, NEW.expires_at)
          IS DISTINCT FROM
          (OLD.id, OLD.user_id, OLD.account_id, OLD.name, OLD.key_hash, OLD.key_prefix, OLD.currency,
           OLD.per_payment_limit, OLD.daily_limit, OLD.ask_above_limit, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'Agent mandate % can only be revoked, once', OLD.id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_mandates_revoke_only BEFORE UPDATE OR DELETE ON agent_mandates
    FOR EACH ROW EXECUTE FUNCTION enforce_mandate_revocation();

-- Allowed payees; a mandate without any may pay any account in its currency.
CREATE TABLE agent_mandate_payees (
    mandate_id UUID NOT NULL REFERENCES agent_mandates(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    PRIMARY KEY (mandate_id, account_id)
);
CREATE TRIGGER agent_mandate_payees_append_only BEFORE UPDATE OR DELETE ON agent_mandate_payees
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Spending per mandate per UTC day. auto_spent is what was posted without asking and
-- can never exceed the daily limit; approved_spent is what the owner approved above it.
CREATE TABLE agent_mandate_usage (
    mandate_id UUID NOT NULL REFERENCES agent_mandates(id),
    day DATE NOT NULL,
    auto_spent NUMERIC(21,2) NOT NULL DEFAULT 0 CHECK (auto_spent >= 0),
    approved_spent NUMERIC(21,2) NOT NULL DEFAULT 0 CHECK (approved_spent >= 0),
    PRIMARY KEY (mandate_id, day)
);
CREATE FUNCTION enforce_mandate_daily_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.auto_spent > (SELECT daily_limit FROM agent_mandates WHERE id = NEW.mandate_id) THEN
        RAISE EXCEPTION 'Agent mandate % would exceed its daily limit', NEW.mandate_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.auto_spent < OLD.auto_spent OR NEW.approved_spent < OLD.approved_spent
                             OR NEW.mandate_id <> OLD.mandate_id OR NEW.day <> OLD.day) THEN
        RAISE EXCEPTION 'Agent mandate usage only grows' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_mandate_usage_within_limit BEFORE INSERT OR UPDATE ON agent_mandate_usage
    FOR EACH ROW EXECUTE FUNCTION enforce_mandate_daily_limit();
CREATE TRIGGER agent_mandate_usage_no_delete BEFORE DELETE ON agent_mandate_usage
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

-- Transfers caused by an agent name its mandate.
ALTER TABLE banking_transactions ADD COLUMN agent_mandate_id UUID REFERENCES agent_mandates(id);
CREATE INDEX idx_transactions_agent_mandate ON banking_transactions(agent_mandate_id) WHERE agent_mandate_id IS NOT NULL;

-- Every payment an agent attempted, and what became of it.
CREATE TABLE agent_payments (
    id UUID PRIMARY KEY,
    mandate_id UUID NOT NULL REFERENCES agent_mandates(id),
    request_key VARCHAR(100) NOT NULL UNIQUE,
    payee_account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL REFERENCES currencies(code),
    intent VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('POSTED', 'PENDING_APPROVAL', 'APPROVED', 'DECLINED', 'EXPIRED', 'REJECTED')),
    reason VARCHAR(200),
    transaction_id UUID UNIQUE REFERENCES banking_transactions(id),
    hold_id UUID UNIQUE REFERENCES holds(id),
    -- The day whose usage the payment counted against; set once it is paid.
    usage_day DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    decided_by UUID REFERENCES app_users(id),
    CONSTRAINT agent_payment_paid_fields CHECK (
        (status IN ('POSTED', 'APPROVED')) = (transaction_id IS NOT NULL AND usage_day IS NOT NULL)),
    CONSTRAINT agent_payment_hold_iff_asked CHECK (
        (status IN ('PENDING_APPROVAL', 'APPROVED', 'DECLINED', 'EXPIRED')) = (hold_id IS NOT NULL)),
    CONSTRAINT agent_payment_rejection_has_reason CHECK (status <> 'REJECTED' OR reason IS NOT NULL),
    CONSTRAINT agent_payment_decided_iff_final CHECK (
        (status IN ('APPROVED', 'DECLINED', 'EXPIRED')) = (decided_at IS NOT NULL))
);
CREATE INDEX idx_agent_payments_mandate ON agent_payments(mandate_id, created_at DESC);
CREATE INDEX idx_agent_payments_pending ON agent_payments(mandate_id) WHERE status = 'PENDING_APPROVAL';

-- Only a payment waiting for approval changes, once, to its final state.
CREATE FUNCTION enforce_agent_payment_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING_APPROVAL'
       OR NEW.status NOT IN ('APPROVED', 'DECLINED', 'EXPIRED')
       OR (NEW.id, NEW.mandate_id, NEW.request_key, NEW.payee_account_id, NEW.amount, NEW.currency, NEW.intent,
           NEW.hold_id, NEW.created_at)
          IS DISTINCT FROM
          (OLD.id, OLD.mandate_id, OLD.request_key, OLD.payee_account_id, OLD.amount, OLD.currency, OLD.intent,
           OLD.hold_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Agent payment % cannot change from %', OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_payments_decide_once BEFORE UPDATE OR DELETE ON agent_payments
    FOR EACH ROW EXECUTE FUNCTION enforce_agent_payment_decision();

-- Events for agent payments, so the owner can be told when one needs a decision.
ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_event_type_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_event_type_check CHECK (event_type IN
    ('transaction.posted', 'hold.created', 'hold.posted', 'hold.voided', 'hold.expired',
     'agent_payment.posted', 'agent_payment.approval_requested', 'agent_payment.approved',
     'agent_payment.declined', 'agent_payment.rejected'));
