-- Linked transfers: a group of transfers that post together or not at all.
--
-- Each leg is an ordinary TRANSFER with its own ledger entries and event, tagged
-- with the group's link_id and its position. The group's request key is stored on
-- leg 0 only, so the existing UNIQUE(request_key) makes the whole group
-- exactly-once; the other legs have no request key.

ALTER TABLE banking_transactions
    ADD COLUMN link_id UUID,
    ADD COLUMN link_index SMALLINT,
    ADD CONSTRAINT link_fields_together CHECK ((link_id IS NULL) = (link_index IS NULL)),
    ADD CONSTRAINT linked_legs_are_transfers CHECK (link_id IS NULL OR transaction_type = 'TRANSFER'),
    ADD CONSTRAINT link_index_range CHECK (link_index IS NULL OR link_index BETWEEN 0 AND 99),
    ADD CONSTRAINT link_key_on_first_leg CHECK (link_id IS NULL OR (link_index = 0) = (request_key IS NOT NULL));

CREATE UNIQUE INDEX idx_tx_link ON banking_transactions(link_id, link_index) WHERE link_id IS NOT NULL;
