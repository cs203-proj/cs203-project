package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.warnings.WarningSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One active warning for the signed-in user, with how far to trust it and why it applies to them")
public record MyWarningResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
        @Schema(description = "Event the warning was drafted from", example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")
        UUID eventId,
        @Schema(example = "HIGH") WarningSeverity severity,
        @Schema(example = "east") String region,
        @Schema(example = "VULNERABLE", allowableValues = {"GENERAL_PUBLIC", "VULNERABLE", "HEALTHCARE_WORKER"})
        String audience,
        @Schema(description = "The wording to read: the admin's edit if the warning was edited, otherwise the "
                + "original draft") String text,
        @Schema(description = "When an admin approved the warning", example = "2026-10-14T02:40:00Z")
        Instant approvedAt,
        Confidence confidence,
        @Schema(description = "Why this user is being told this. Built in code from the user's profile, never by the AI",
                example = "You are in the east region and receive advice for vulnerable groups.")
        String whyThisApplies,
        @Schema(description = "Extra personalised sentence written by the AI, if there is one. Always labelled")
        AiLine aiLine,
        @Schema(description = "Set only when the readings behind the warning were stale or incomplete; null otherwise",
                example = "Based on partial data: 4 of 6 expected readings in the last 6 hours.")
        String dataQualityNote
) {

    @Schema(description = "How far to trust the warning. Computed from measured inputs, not by the AI")
    public record Confidence(
            @Schema(description = "LOW, MEDIUM or HIGH; null if the event was never scored", example = "MEDIUM")
            ConfidenceLevel level,
            @Schema(description = "What the level means in plain language",
                    example = "Moderate confidence: the signal looks real, but some checks are weaker. Treat it as a "
                            + "likely warning and check for updates.")
            String meaning
    ) {
    }

    @Schema(description = "A sentence written by the AI, kept apart from the code-generated explanation")
    public record AiLine(
            @Schema(example = "Fine particles at these levels can worsen breathing for people with asthma or heart "
                    + "disease.") String text,
            @Schema(example = "AI-generated") String label
    ) {
    }
}
