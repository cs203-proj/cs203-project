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
        @Schema(example = "GENERAL_PUBLIC") String audience,
        @Schema(example = "HIGH") WarningSeverity severity,
        @Schema(example = "PENDING_REVIEW") WarningStatus status,
        @Schema(description = "The AI's original draft, never overwritten") String warningText,
        @Schema(description = "When the draft was generated", example = "2026-10-14T02:10:00Z") Instant createdAt
) {
    public static WarningResponse from(Warning warning) {
        return new WarningResponse(warning.getId(), warning.getEventId(), warning.getAudience(),
                warning.getSeverity(), warning.getStatus(), warning.getWarningText(), warning.getCreatedAt());
    }
}
