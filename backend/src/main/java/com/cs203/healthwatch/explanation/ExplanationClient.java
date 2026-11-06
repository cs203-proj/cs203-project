package com.cs203.healthwatch.explanation;

/**
 * Asks an AI model to explain a detected event. Implementations throw {@link ExplanationException} (or any runtime
 * exception) on failure; {@link EventExplanationService} turns every failure into a FAILED row, never a crash.
 * Tests mock this interface, so CI never calls a live API.
 */
public interface ExplanationClient {

    AiExplanation explain(ExplanationContext context);

    /** Model name stored with each explanation, so results can be traced to the model that wrote them. */
    String modelName();
}
