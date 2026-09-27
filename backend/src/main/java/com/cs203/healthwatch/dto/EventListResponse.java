package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of flagged events, largest deviation first")
public record EventListResponse(
        List<EventResponse> items,
        @Schema(description = "Total events matching the filter, across all pages", example = "12") long total,
        @Schema(description = "Maximum items returned in this page", example = "50") int limit,
        @Schema(description = "Number of events skipped before this page", example = "0") long offset
) {
}
