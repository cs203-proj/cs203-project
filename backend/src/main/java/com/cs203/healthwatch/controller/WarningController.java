package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.WarningAuditEntryResponse;
import com.cs203.healthwatch.dto.WarningListResponse;
import com.cs203.healthwatch.service.AuditService;
import com.cs203.healthwatch.service.WarningService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/warnings")
@RequiredArgsConstructor
@Tag(name = "Warnings", description = "Drafted warnings and their audit trail (admin only)")
public class WarningController {

    private final AuditService auditService;
    private final WarningService warningService;

    @GetMapping("/{id}/audit")
    @Operation(
            summary = "Get a warning's audit trail",
            description = "Every action on the warning, oldest first, with who did it, when, and the "
                    + "before/after state. Entries are append-only. Returns an empty list if there are none.")
    public List<WarningAuditEntryResponse> auditTrail(
            @Parameter(description = "Warning id") @PathVariable UUID id) {
        return auditService.trail(id);
    }

    @GetMapping
    @Operation(
            summary = "Warning review queue",
            description = "Returns warnings sorted by severity (CRITICAL, HIGH, MEDIUM, LOW), then by age with the "
                    + "oldest first, so the most serious warning that has waited longest is at the top. Use "
                    + "status=PENDING_REVIEW for the review queue. Results are paged with limit and offset.")
    @ApiResponse(responseCode = "200", description = "Warnings returned (an empty list if none match)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = WarningListResponse.class),
                    examples = @ExampleObject(name = "Two pending warnings", value = """
                            {
                              "items": [
                                {
                                  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                  "eventId": "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
                                  "audience": "GENERAL_PUBLIC",
                                  "severity": "HIGH",
                                  "status": "PENDING_REVIEW",
                                  "warningText": "PM2.5 in the central region has stayed well above normal for 3 hours. Confidence: medium. Limit prolonged outdoor activity.",
                                  "createdAt": "2026-10-14T02:10:00Z"
                                },
                                {
                                  "id": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
                                  "eventId": "8d1e4a72-5c3b-49f0-a6e2-7b9c0d1e2f34",
                                  "audience": "GENERAL_PUBLIC",
                                  "severity": "MEDIUM",
                                  "status": "PENDING_REVIEW",
                                  "warningText": "PM2.5 in the central region is elevated. Confidence: low. Sensitive groups should reduce outdoor exertion.",
                                  "createdAt": "2026-10-14T01:05:00Z"
                                }
                              ],
                              "total": 2,
                              "limit": 50,
                              "offset": 0
                            }
                            """)))
    @ApiResponse(responseCode = "400", description = "Unknown status, or limit/offset out of range",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-10-14T03:20:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Unknown status 'FOO'. Valid values: PENDING_REVIEW, APPROVED, SUPPRESSED",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public WarningListResponse queue(
            @Parameter(description = "Only return warnings with this status: PENDING_REVIEW, APPROVED or SUPPRESSED "
                    + "(case-insensitive). Omit to return all statuses.", example = "PENDING_REVIEW")
            @RequestParam(required = false) String status,
            @Parameter(description = "Maximum number of warnings to return (1-200)", example = "50")
            @RequestParam(defaultValue = "" + WarningService.DEFAULT_LIMIT) int limit,
            @Parameter(description = "Number of warnings to skip, for paging", example = "0")
            @RequestParam(defaultValue = "0") long offset) {
        return warningService.queue(status, limit, offset);
    }
}