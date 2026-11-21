package com.smartbank.idempotency;

/** The key was already used, within its retention window, for a different request. */
public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException() {
        super("This idempotency key was already used for a different request");
    }
}
