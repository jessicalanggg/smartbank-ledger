-- Client idempotency keys, scoped per user, with a stored response and a retention window.
--
-- A client key maps to a server-generated operation_key. The operation key is what
-- money-moving code writes to banking_transactions.request_key / holds.request_key,
-- whose UNIQUE constraints keep each operation exactly-once even if the server
-- crashes after posting but before the response was stored here: the retry runs
-- the same operation key again and gets the original transaction back.
--
-- response_body is TEXT, not JSONB, so replays return the exact original bytes.

CREATE TABLE idempotency_keys (
    user_id UUID NOT NULL REFERENCES app_users(id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    operation_key UUID NOT NULL UNIQUE,
    state VARCHAR(10) NOT NULL CHECK (state IN ('STARTED', 'COMPLETED')),
    response_status SMALLINT,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT idempotency_completed_fields CHECK (
        (state = 'COMPLETED') = (response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT idempotency_expires_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
