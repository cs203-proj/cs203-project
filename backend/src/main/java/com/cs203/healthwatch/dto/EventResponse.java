package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.model.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A flagged event awaiting or under investigation")
public record EventResponse(
        @Schema(example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b") UUID id,
        @Schema(description = "When the deviation was detected", example = "2026-09-24T03:15:00Z") Instant timestamp,
        @Schema(example = "AQI") String signalType,
        @Schema(example = "Singapore") String region,
        @Schema(example = "NEW") EventStatus status,
        @Schema(description = "How far the reading deviated from its baseline", example = "4.2") Double deviationSize
) {
}
