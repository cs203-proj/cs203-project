package com.cs203.healthwatch.detection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
        String region,
        double zThreshold,
        int consecutiveReadings,
        Duration maxGap
) {}
