package com.smartbank.ledger;

/** The ledger accounts that entries can post to. */
public enum LedgerAccount {
    /** A customer's bank account; entries name the account. */
    CUSTOMER,
    /** Money entering or leaving the bank through deposits and withdrawals. */
    EXTERNAL_CLEARING,
    /** Balances that existed before the ledger was introduced. */
    OPENING_EQUITY
}
