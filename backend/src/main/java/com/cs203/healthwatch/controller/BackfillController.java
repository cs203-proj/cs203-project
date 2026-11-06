package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.BackfillResponse;
import com.cs203.healthwatch.ingestion.pm.PmBackfillService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/backfill")
@RequiredArgsConstructor
@Tag(name = "Backfill", description = "Load historical readings into public.readings (admin only)")
public class BackfillController {

    private final PmBackfillService backfillService;

    @PostMapping("/pm25")
    @Operation(
            summary = "Backfill historical PM2.5 readings",
            description = "Fetches every day in the range (SGT dates, both inclusive) from data.gov.sg and stores "
                    + "readings for every configured region through the normal ingestion path. At most "
                    + "${ingestion.backfill.max-days} days per call; call again for longer ranges. Safe to rerun: "
                    + "readings already stored are counted as duplicates and skipped. Days that could not be "
                    + "fetched or stored completely (fetch failed, unusable response, or rows that failed to save) "
                    + "are listed in failedDays; rerun those days.")
    @ApiResponse(responseCode = "200", description = "Backfill finished (check failedDays)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = BackfillResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "from": "2026-08-01",
                              "to": "2026-08-31",
                              "days": 31,
                              "pages": 31,
                              "saved": 3716,
                              "malformed": 4,
                              "duplicates": 0,
                              "failed": 0,
                              "failedDays": []
                            }
                            """)))
    @ApiResponse(responseCode = "400", description = "Missing or invalid dates, from after to, a future date, "
            + "or more than ${ingestion.backfill.max-days} days",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public BackfillResponse backfillPm25(
            @Parameter(description = "First day, YYYY-MM-DD (SGT)", example = "2026-08-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, YYYY-MM-DD (SGT), inclusive", example = "2026-08-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return backfillService.backfill(from, to);
    }
}
