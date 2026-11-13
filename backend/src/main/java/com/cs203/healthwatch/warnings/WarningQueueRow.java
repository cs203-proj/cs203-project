package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import java.util.List;

/**
 * One review-queue row (CG-165): a warning plus the AI and confidence columns of its event, read in the same query.
 * Every column after {@code warning} is null when there is nothing to join (no explanation row, or no confidence); the scope columns are also null on explanations written before V16 (CG-164).
 */
public record WarningQueueRow(
        Warning warning,
        ExplanationStatus explanationStatus,
        Assessment assessment,
        String likelyCause,
        Double confidenceScore,
        ConfidenceLevel confidenceLevel,
        SpatialScope scope,
        List<String> regionsAbove,
        List<String> regionsCompared,
        String scopeSummary
) {}
