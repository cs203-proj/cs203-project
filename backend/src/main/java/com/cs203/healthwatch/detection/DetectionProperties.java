package com.cs203.healthwatch.detection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
        String region,
        String signalType,
        String sourceType,
        double zThreshold,
        int consecutiveReadings,
        Duration maxGap,
        Replay replay
) {
    /** The historical window POST /demo/replay pushes through detection, read from baseline_dev.readings. */
    public record Replay(String region, String signalType, String from, String to) {}
}
