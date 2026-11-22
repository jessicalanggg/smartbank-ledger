-- The application connects as its own role, smartbank_app, which owns nothing.
--
-- Migrations run as the schema owner; the running app does not. So the app cannot
-- ALTER a table, DISABLE its triggers or DROP it (all need ownership), cannot
-- TRUNCATE anything, and cannot DELETE from or UPDATE ledger history: those tables
-- grant it only SELECT and INSERT, and their append-only triggers, which only the
-- owner could switch off, refuse the rest. A bug or a compromised app server can
-- add to the books but not rewrite them.
--
-- The role is created without a password; the application sets one after each
-- migration (smartbank.db.app-password), so no secret is stored here. Creating it
-- needs CREATEROLE (local Docker and test databases have it; a managed database's
-- admin can create the role beforehand instead, and this block then skips it).
-- Every new table must be added below; a test fails for any table that is not.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartbank_app') THEN
        CREATE ROLE smartbank_app NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO smartbank_app;

-- History: added to, never changed. (No UPDATE, DELETE or TRUNCATE.)
GRANT SELECT, INSERT ON ledger_entries, outbox_events, ledger_seals, ledger_sealed_transactions,
    ledger_checkpoints, ledger_checkpoint_runs, fx_rates, agent_mandate_payees, ledger_accounts
    TO smartbank_app;
GRANT SELECT, INSERT ON banking_transactions TO smartbank_app;
-- Reversals lock the original transaction row (FOR NO KEY UPDATE), which PostgreSQL
-- allows only with UPDATE privilege on some column. One column is enough; the
-- append-only trigger still rejects every actual update.
GRANT UPDATE (reverses_transaction_id) ON banking_transactions TO smartbank_app;

-- Reference data.
GRANT SELECT ON currencies, journal_lines TO smartbank_app;

-- Live state, changed in place under the rules of each table's triggers.
GRANT SELECT, INSERT, UPDATE ON accounts, holds, fx_quotes, agent_mandates, agent_mandate_usage, agent_payments,
    webhook_endpoints, webhook_deliveries TO smartbank_app;
GRANT SELECT, INSERT ON app_users TO smartbank_app;

-- Short-lived records the app prunes itself.
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys, rate_limit_buckets TO smartbank_app;
