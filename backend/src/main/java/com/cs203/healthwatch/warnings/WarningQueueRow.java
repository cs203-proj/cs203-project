package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.ExplanationStatus;

/**
 * One review-queue row (CG-165): a warning plus the AI and confidence columns of its event, read in the same query.
 * Every column after {@code warning} is null when there is nothing to join (no explanation row, or no confidence).
 */
public record WarningQueueRow(
        Warning warning,
        ExplanationStatus explanationStatus,
        Assessment assessment,
        String likelyCause,
        Double confidenceScore,
        ConfidenceLevel confidenceLevel
) {}
