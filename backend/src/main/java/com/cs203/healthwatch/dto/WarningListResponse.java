package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of the warning review queue, most severe first, then oldest first")
public record WarningListResponse(
        List<WarningResponse> items,
        @Schema(description = "Total warnings matching the filter, across all pages", example = "4") long total,
        @Schema(description = "Maximum items returned in this page", example = "50") int limit,
        @Schema(description = "Number of warnings skipped before this page", example = "0") long offset
) {
}
