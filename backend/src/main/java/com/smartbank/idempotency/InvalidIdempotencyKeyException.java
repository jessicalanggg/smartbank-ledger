package com.smartbank.idempotency;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException(String message) { super(message); }
}
