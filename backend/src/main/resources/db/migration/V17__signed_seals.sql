-- Signed seals: each seal's hash can be signed with an Ed25519 key that the database
-- never holds (see SealSigner). key_id names the public key that verifies it.
--
-- A seal may now cover no transactions: when signing is first switched on, the
-- sealer appends one such signed seal so that everything sealed before is covered.

ALTER TABLE ledger_seals
    ADD COLUMN key_id CHAR(16),
    ADD COLUMN signature TEXT,
    ADD CONSTRAINT seal_signature_has_key CHECK ((key_id IS NULL) = (signature IS NULL));
ALTER TABLE ledger_seals DROP CONSTRAINT ledger_seals_transaction_count_check;
ALTER TABLE ledger_seals ADD CONSTRAINT ledger_seals_transaction_count_check CHECK (transaction_count >= 0);
