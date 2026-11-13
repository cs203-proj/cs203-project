package com.cs203.healthwatch.warnings;

/**
 * Asks an AI model for the "why this matters" line for a warning's audience (CG-154). Implementations throw on any
 * failure; {@link AiLineService} catches it and leaves the warning without an AI line. Tests mock this interface, so
 * CI never calls a live API.
 */
public interface AiLineClient {

    /** False when there is no API key, so no call is attempted. */
    boolean isConfigured();

    /** @return the raw sentence from the model; {@link AiLineService} cleans and checks it */
    String write(AiLineContext context);
}
