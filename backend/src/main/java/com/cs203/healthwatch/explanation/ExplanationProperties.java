package com.cs203.healthwatch.explanation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for AI explanations of detected events (CG-135), under app.explanation.
 *
 * @param enabled             false skips explanations entirely (no AI call, no row)
 * @param apiKey              Gemini API key; blank means every attempt is recorded as FAILED "not configured"
 * @param model               Gemini model code, e.g. gemini-3.8-flash
 * @param baseUrl             Gemini REST base URL
 * @param timeout             connect + read timeout for one AI call
 * @param lookback            how far back the data-quality check and the evidence readings go
 * @param expectedInterval    how often the feed should produce a reading (hourly for PM2.5)
 * @param staleAfter          latest reading older than this (at analysis time) is called stale
 * @param maxEvidenceReadings most recent readings sent to the AI
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
        int maxEvidenceReadings
) {
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
