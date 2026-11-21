-- Transactional outbox and webhooks.
--
-- outbox_events is written in the same database transaction as the change it
-- describes, so an event exists if and only if that change committed. Delivery
-- rows for subscribed webhook endpoints are created in that same transaction.
-- A background worker then delivers them over HTTP, at least once, with signed
-- payloads, retries with exponential backoff, and a dead-letter state.

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN
        ('transaction.posted', 'hold.created', 'hold.posted', 'hold.voided', 'hold.expired')),
    aggregate_id UUID NOT NULL,
    account_ids UUID[] NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id);
CREATE INDEX idx_outbox_created ON outbox_events(created_at);

CREATE TRIGGER outbox_events_append_only
    BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(100) NOT NULL,
    event_types VARCHAR(40)[],
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_webhook_endpoints_user ON webhook_endpoints(user_id) WHERE active;

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_events(id),
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until TIMESTAMP WITH TIME ZONE,
    last_status_code INT,
    last_error VARCHAR(500),
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (event_id, endpoint_id),
    CONSTRAINT delivered_has_time CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries(next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries(endpoint_id, created_at DESC);

-- Backfill events for history recorded before the outbox existed. No endpoints
-- exist yet, so this creates no deliveries.
INSERT INTO outbox_events (id, event_type, aggregate_id, account_ids, payload, created_at)
SELECT gen_random_uuid(), 'transaction.posted', t.id,
       array_remove(ARRAY[t.source_account_id, t.destination_account_id], NULL),
       json_build_object('id', t.id, 'type', t.transaction_type, 'fromAccountId', t.source_account_id,
                         'toAccountId', t.destination_account_id, 'amount', t.amount,
                         'currency', t.currency, 'createdAt', t.created_at)::text,
       t.created_at
FROM banking_transactions t
WHERE t.transaction_type <> 'OPENING_BALANCE';

INSERT INTO outbox_events (id, event_type, aggregate_id, account_ids, payload, created_at)
SELECT gen_random_uuid(), e.event_type, h.id, ARRAY[h.source_account_id, h.destination_account_id],
       json_build_object('id', h.id, 'fromAccountId', h.source_account_id, 'toAccountId', h.destination_account_id,
                         'amount', h.amount, 'currency', h.currency, 'status', h.status,
                         'postedAmount', h.posted_amount, 'postedTransactionId', h.posted_transaction_id,
                         'createdAt', h.created_at, 'expiresAt', h.expires_at, 'resolvedAt', h.resolved_at)::text,
       e.at
FROM holds h
CROSS JOIN LATERAL (
    SELECT 'hold.created' AS event_type, h.created_at AS at
    UNION ALL
    SELECT 'hold.' || lower(h.status), h.resolved_at WHERE h.status <> 'PENDING'
) e;
