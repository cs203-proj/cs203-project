package com.cs203.healthwatch.explanation;

import java.time.Duration;

/**
 * The AI provider answered 429. The message starts with {@link #PREFIX} so a stored error shows it was a rate limit,
 * not a bad key or a bad reply.
 */
public class RateLimitedException extends ExplanationException {

    public static final String PREFIX = "RATE_LIMITED:";

    private final Duration retryAfter;

    /** @param retryAfter the provider's Retry-After, or null if it sent none */
    public RateLimitedException(String message, Duration retryAfter, Throwable cause) {
        super(PREFIX + " " + message, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
