package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /warnings/{id}/suppress (CG-28). A reason is required: suppressing means the public is deliberately
 * not told about a confirmed event, so the audit trail has to record why.
 */
@Schema(description = "Why the warning is being suppressed")
public record SuppressWarningRequest(
        @Schema(description = "Why this warning should not go out. Recorded in the audit trail.",
                example = "Duplicate of the warning already approved for the same haze episode.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "reason is required")
        String reason
) {
}
