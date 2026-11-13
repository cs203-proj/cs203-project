package com.cs203.healthwatch.explanation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for AI explanations of detected events (CG-135), under app.explanation.
 *
 * @param enabled             false skips explanations entirely (no AI call, no row)
 * @param apiKey              Groq API key; blank means every attempt is recorded as FAILED "not configured"
 * @param model               Groq model id, e.g. openai/gpt-oss-120b (must support strict json_schema)
 * @param baseUrl             Groq OpenAI-compatible base URL
 * @param timeout             connect + read timeout for one AI call
 * @param lookback            how far back the data-quality check and the evidence readings go
 * @param expectedInterval    how often the feed should produce a reading (hourly for PM2.5)
 * @param staleAfter          latest reading older than this (at analysis time) is called stale
 * @param maxEvidenceReadings most recent readings sent to the AI
 * @param retry               how FAILED explanations are retried (CG-152)
 */
@ConfigurationProperties(prefix = "app.explanation")
public record ExplanationProperties(
        boolean enabled,
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        Duration lookback,
        Duration expectedInterval,
        Duration staleAfter,
        int maxEvidenceReadings,
        Retry retry
) {
    /**
     * @param interval    pause between retry runs (also the first delay after startup)
     * @param batchSize   most explanations retried per run, so a backlog cannot burn the free-plan quota at once
     * @param maxAttempts total tries per event (the first try included); after this a FAILED row is left alone
     */
    public record Retry(Duration interval, int batchSize, int maxAttempts) {}

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
