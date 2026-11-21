-- Webhook signing secrets are stored encrypted (AES-256-GCM, by the application),
-- and can be rotated with an overlap during which both secrets sign deliveries.
-- Existing plain-text secrets are encrypted by the application at startup, which
-- then clears the plain-text column.
ALTER TABLE webhook_endpoints
    ALTER COLUMN secret DROP NOT NULL,
    ADD COLUMN secret_encrypted TEXT,
    ADD COLUMN previous_secret_encrypted TEXT,
    ADD COLUMN previous_secret_expires_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT webhook_secret_present CHECK (secret IS NOT NULL OR secret_encrypted IS NOT NULL),
    ADD CONSTRAINT webhook_previous_secret_fields CHECK (
        (previous_secret_encrypted IS NULL) = (previous_secret_expires_at IS NULL));
