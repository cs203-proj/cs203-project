package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of PATCH /events/{id}/status. There is intentionally no actor field: the actor is always taken from the
 * caller's token, and any extra field in the body (e.g. "actor") is ignored.
 */
@Schema(description = "The status to move the event to")
public record UpdateEventStatusRequest(
        @Schema(description = "New status: UNDER_REVIEW, CONFIRMED or DISMISSED (case-insensitive)",
                example = "UNDER_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "status is required")
        String status
) {
}
