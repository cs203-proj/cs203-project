package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.events.DetectedEvent;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How far to trust an event: a computed score, its band, and the inputs it came from")
public record ConfidenceResponse(
        @Schema(description = "Raw score from 0 (no trust) to 1", example = "0.77") double score,
        @Schema(example = "HIGH") ConfidenceLevel level,
        @Schema(description = "Version of the formula that produced the score", example = "1") int formulaVersion,
        Inputs inputs
) {
    @Schema(description = "The measured inputs the score was computed from")
    public record Inputs(
            @Schema(description = "Seconds between the newest reading and scoring", example = "1800") long stalenessSeconds,
            @Schema(description = "Valid readings in the 24h window before the event", example = "23") int readingCount,
            @Schema(description = "Robust z-score of the event (same as deviationSize)", example = "4.2") Double deviationSize,
            @Schema(description = "Share of the other regions that also show a spike, 0..1; null when no other region is comparable",
                    example = "0.75") Double sourceAgreement
    ) {}

    /** Null for events created before confidence scoring existed. */
    public static ConfidenceResponse from(DetectedEvent e) {
        if (e.getConfidenceScore() == null) {
            return null;
        }
        return new ConfidenceResponse(e.getConfidenceScore(), e.getConfidenceLevel(), e.getConfidenceFormulaVersion(),
                new Inputs(e.getConfidenceStalenessSeconds(), e.getConfidenceReadingCount(), e.getDeviation(),
                        e.getConfidenceSourceAgreement()));
    }
}
