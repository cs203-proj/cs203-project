package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Outcome of a historical PM2.5 backfill")
public record BackfillResponse(
        @Schema(example = "2026-08-01") LocalDate from,
        @Schema(example = "2026-08-31") LocalDate to,
        @Schema(description = "Days requested", example = "31") int days,
        @Schema(description = "API pages fetched", example = "31") int pages,
        @Schema(description = "New rows stored, valid or malformed", example = "3720") int saved,
        @Schema(description = "Of the saved rows, how many are flagged malformed", example = "4") int malformed,
        @Schema(description = "Rows already stored, skipped", example = "0") int duplicates,
        @Schema(description = "Rows that failed to save for another reason", example = "0") int failed,
        @Schema(description = "Days that could not be fetched or stored completely; rerun the backfill for these")
        List<LocalDate> failedDays
) {
}
