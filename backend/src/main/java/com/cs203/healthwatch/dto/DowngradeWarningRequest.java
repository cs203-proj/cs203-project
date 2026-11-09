package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /warnings/{id}/downgrade (CG-28). A reason is required: lowering a public health warning is a
 * judgement call, so the audit trail has to record why it was made.
 */
@Schema(description = "The lower severity to move the warning to, and why")
public record DowngradeWarningRequest(
        @Schema(description = "New severity, which must be less serious than the current one: LOW, MEDIUM, HIGH or "
                + "CRITICAL (case-insensitive)", example = "MEDIUM",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "severity is required")
        String severity,

        @Schema(description = "Why the severity is being lowered. Recorded in the audit trail.",
                example = "Readings fell back towards the baseline within the hour.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "reason is required")
        String reason
) {
}
