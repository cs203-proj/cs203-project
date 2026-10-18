package com.cs203.healthwatch.explanation;

/** The AI explanation could not be produced: not configured, call failed, or the reply was unusable. */
public class ExplanationException extends RuntimeException {

    public ExplanationException(String message) {
        super(message);
    }

    public ExplanationException(String message, Throwable cause) {
        super(message, cause);
    }
}
