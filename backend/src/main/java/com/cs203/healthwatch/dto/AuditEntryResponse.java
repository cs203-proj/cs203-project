package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.events.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One recorded status change on an event")
public record AuditEntryResponse(
        @Schema(example = "17") Long id,
        @Schema(description = "Id of the admin who made the change", example = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        UUID actorId,
        @Schema(description = "Username of the admin who made the change, if the account still exists",
                example = "admin")
        String actorUsername,
        @Schema(example = "NEW") EventStatus oldStatus,
        @Schema(example = "UNDER_REVIEW") EventStatus newStatus,
        @Schema(example = "2026-09-24T04:02:11Z") Instant changedAt
) {
}
