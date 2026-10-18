package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.detection.ReadingSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared fixtures for the CG-135 tests. */
final class TestExplanations {

    private TestExplanations() {
    }

    static ExplanationProperties props(boolean enabled, String apiKey) {
        return new ExplanationProperties(enabled, apiKey, "gemini-test-model",
                "https://generativelanguage.googleapis.com/v1beta", Duration.ofSeconds(5), Duration.ofHours(6),
                Duration.ofMinutes(60), Duration.ofHours(2), 12);
    }

    /** One reading per hour, the last one at {@code last}, oldest first. */
    static List<ReadingSnapshot> hourly(Instant last, double... values) {
        List<ReadingSnapshot> readings = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Instant at = last.minus(Duration.ofHours(values.length - 1 - i));
            readings.add(new ReadingSnapshot("central", "pm25", values[i], at));
        }
        return readings;
    }

    static ExplanationContext context(boolean replay, DataQuality quality, List<ReadingSnapshot> readings) {
        return new ExplanationContext(UUID.fromString("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"), "central", "pm25",
                Instant.parse("2026-10-18T03:00:00Z"), 6.12, replay, 18.0, 5.0, 3.0, 3, readings, quality);
    }
}
