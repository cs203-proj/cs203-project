package com.cs203.healthwatch.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "One recorded action on a warning")
public record WarningAuditEntryResponse(
        @Schema(example = "17") Long id,
        @Schema(description = "User id from the token, or 'system' for system actions", example = "system")
        String actor,
        @Schema(example = "DRAFT_CREATED") String action,
        @Schema(description = "State before the action; null on creation") JsonNode before,
        @Schema(description = "State after the action") JsonNode after,
        @Schema(example = "2026-09-24T04:02:11Z") Instant createdAt
) {
}