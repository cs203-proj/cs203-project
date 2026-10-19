package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A warning drafted from a confirmed event, waiting for admin review")
public record WarningResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
        @Schema(example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b") UUID eventId,
        @Schema(example = "GENERAL_PUBLIC") String audience,
        @Schema(example = "HIGH") WarningSeverity severity,
        @Schema(example = "PENDING_REVIEW") WarningStatus status,
        @Schema(description = "The original generated draft. Never overwritten; admin edits are stored separately "
                + "(CG-28).") String warningText,
        @Schema(example = "2026-09-24T06:47:30Z") Instant createdAt
) {
    public static WarningResponse from(Warning warning) {
        return new WarningResponse(warning.getId(), warning.getEventId(), warning.getAudience(),
                warning.getSeverity(), warning.getStatus(), warning.getWarningText(), warning.getCreatedAt());
    }
}
