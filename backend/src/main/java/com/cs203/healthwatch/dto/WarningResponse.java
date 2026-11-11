package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A generated warning in the review queue")
public record WarningResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
        @Schema(description = "Event the warning was drafted from", example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")
        UUID eventId,
        @Schema(description = "Who the draft is for. Same codes as the profile audience (CG-6)", example = "VULNERABLE",
                allowableValues = {"GENERAL_PUBLIC", "VULNERABLE", "HEALTHCARE_WORKER"}) String audience,
        @Schema(example = "HIGH") WarningSeverity severity,
        @Schema(example = "PENDING_REVIEW") WarningStatus status,
        @Schema(description = "The AI's original draft, never overwritten") String warningText,
        @Schema(description = "The admin's edited wording (CG-28), or null if it has not been edited. The AI original "
                + "stays in warningText, so a reviewer can always compare the two.")
        String editedText,
        @Schema(description = "Optional AI-written \"why this matters for this audience\" line (CG-154), built from "
                + "the event's AI explanation. Never contains advice. Null if there is none. Shown to end users "
                + "labelled as AI-generated",
                example = "Fine particles at these levels can worsen breathing for people with asthma or heart "
                        + "disease, and the rise may be consistent with regional haze.")
        String aiLine, // read by END USERS (GET /me/warnings); per-audience text
        @Schema(description = "When the draft was generated", example = "2026-10-14T02:10:00Z") Instant createdAt,
        @Schema(description = "AI summary of the event (CG-163), for ADMIN triage in the review queue. Filled only by "
                + "GET /warnings; approve, edit, downgrade, suppress and GET /events/{id}/warnings return null.",
                nullable = true)
        WarningAiSummary ai // read by ADMINS; event-level verdict, not per audience
) {
    public static WarningResponse from(Warning warning) {
        return new WarningResponse(warning.getId(), warning.getEventId(), warning.getAudience(),
                warning.getSeverity(), warning.getStatus(), warning.getWarningText(), warning.getEditedText(),
                warning.getAiLine(), warning.getCreatedAt(), null);
    }
}
