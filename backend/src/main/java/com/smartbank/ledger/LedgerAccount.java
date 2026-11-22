package com.smartbank.ledger;

/**
 * The kind of account an entry posts to. Each kind maps onto an account in the
 * chart of accounts (ledger_accounts): CUSTOMER onto the customer's own liability
 * account under 2000 Customer deposits, the others onto the system account of the
 * same kind and currency.
 *
 * Entry amounts are signed: positive is a credit, negative a debit.
 */
public enum LedgerAccount {
    /** A customer's account: a liability of the bank (2000-…). */
    CUSTOMER,
    /** The bank's cash and settlement with the outside world: an asset (1000). */
    EXTERNAL_CLEARING,
    /** Balances that existed before the ledger was introduced: equity (3900). */
    OPENING_EQUITY,
    /** Fees charged to customers: revenue (4000). */
    FEE_REVENUE
}
