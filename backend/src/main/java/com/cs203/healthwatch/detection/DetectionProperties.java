package com.cs203.healthwatch.detection;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
        List<String> regions,
        String signalType,
        String sourceType,
        double zThreshold,
        int consecutiveReadings,
        Duration maxGap,
        Replay replay
) {
    public DetectionProperties {
        regions = regions == null ? List.of() : regions.stream()
                .filter(region -> region != null && !region.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /** Backward-compatible constructor for existing single-region callers and tests. */
    public DetectionProperties(String region, String signalType, String sourceType, double zThreshold,
                               int consecutiveReadings, Duration maxGap, Replay replay) {
        this(List.of(region), signalType, sourceType, zThreshold, consecutiveReadings, maxGap, replay);
    }

    /** The historical window POST /demo/replay pushes through detection, read from public.readings. */
    public record Replay(String region, String signalType, String from, String to) {}
}
