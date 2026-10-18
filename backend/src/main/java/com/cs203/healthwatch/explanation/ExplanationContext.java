package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.detection.ReadingSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the AI is told about one detected event. Only facts the system already holds: no free text from
 * users, so nothing in here can steer the model.
 *
 * @param deviation  the largest robust z-score among the breaching readings
 * @param readings   recent valid readings, oldest first
 */
public record ExplanationContext(
        UUID eventId,
        String region,
        String signalType,
        Instant detectedAt,
        double deviation,
        boolean replay,
        double baselineMedian,
        double baselineScaledMad,
        double zThreshold,
        int consecutiveReadings,
        List<ReadingSnapshot> readings,
        DataQuality dataQuality
) {}
