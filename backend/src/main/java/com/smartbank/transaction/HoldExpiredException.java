package com.smartbank.transaction;
/** Posting was attempted after expiry. The expiry itself is committed, not rolled back. */
public class HoldExpiredException extends BankingException {
    public HoldExpiredException() { super("Hold has expired"); }
}
