package com.cs203.healthwatch.common.exception;

/** The request clashes with current state, e.g. a duplicate name. Mapped to 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
