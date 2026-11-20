package com.smartbank.transaction;

/** PENDING changes exactly once, to one of the final states. */
public enum HoldStatus { PENDING, POSTED, VOIDED, EXPIRED }
