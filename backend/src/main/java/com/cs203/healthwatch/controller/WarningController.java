package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.DowngradeWarningRequest;
import com.cs203.healthwatch.dto.EditWarningRequest;
import com.cs203.healthwatch.dto.SuppressWarningRequest;
import com.cs203.healthwatch.dto.WarningAuditEntryResponse;
import com.cs203.healthwatch.dto.WarningListResponse;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.security.AuthenticatedUser;
import com.cs203.healthwatch.service.AuditService;
import com.cs203.healthwatch.service.WarningReviewService;
import com.cs203.healthwatch.service.WarningService;
import com.cs203.healthwatch.warnings.WarningStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final WarningReviewService warningReviewService;

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
                    + "oldest first, so the most serious warning that has waited longest is at the top. "
                    + "Age is how long the warning has been in the queue (createdAt). Use "
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
            @Parameter(description = "Only return warnings with this status (case-insensitive). Omit to return "
                    + "all statuses.", example = "PENDING_REVIEW",
                    schema = @Schema(implementation = WarningStatus.class))
            @RequestParam(required = false) String status,
            @Parameter(description = "Maximum number of warnings to return (1-200)", example = "50")
            @RequestParam(defaultValue = "" + WarningService.DEFAULT_LIMIT) int limit,
            @Parameter(description = "Number of warnings to skip, for paging", example = "0")
            @RequestParam(defaultValue = "0") long offset) {
        return warningService.queue(status, limit, offset);
    }

    // --- review actions (CG-28) ---------------------------------------------------------------------------------
    // All four work only on a PENDING_REVIEW warning, record who acted in the audit log in the same transaction,
    // and send nothing to end users. The actor always comes from the bearer token, never from the request body.

    @PostMapping("/{id}/approve")
    @Operation(
            summary = "Approve a warning",
            description = "Marks the warning APPROVED and stamps the approval time, which is what the end-user feed "
                    + "uses to decide whether a warning is still active. Works only on a PENDING_REVIEW warning. "
                    + "The approval is written to the audit log in the same transaction. Nothing is sent to end "
                    + "users; delivery is Sprint 3.")
    @ApiResponse(responseCode = "200", description = "Approved; the updated warning is returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = WarningResponse.class)))
    @ApiResponse(responseCode = "400", description = "The warning is not PENDING_REVIEW",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-11-09T14:20:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Warning is APPROVED. Only a PENDING_REVIEW warning can be approved, edited, downgraded or suppressed.",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No warning with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public WarningResponse approve(
            @Parameter(description = "Warning id") @PathVariable UUID id,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        return warningReviewService.approve(id, actor.id());
    }

    @PostMapping("/{id}/edit")
    @Operation(
            summary = "Edit a warning's wording",
            description = "Replaces the wording end users will see. The AI's original draft is never overwritten: it "
                    + "stays in warningText and the edit is returned as editedText, so both can be compared. The "
                    + "warning stays PENDING_REVIEW — editing is not approving, so it still has to be approved "
                    + "before it can go anywhere.")
    @ApiResponse(responseCode = "200", description = "Edited; the updated warning is returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = WarningResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing warningText, or the warning is not PENDING_REVIEW",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No warning with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public WarningResponse edit(
            @Parameter(description = "Warning id") @PathVariable UUID id,
            @Valid @RequestBody EditWarningRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        return warningReviewService.edit(id, request.warningText(), actor.id());
    }

    @PostMapping("/{id}/downgrade")
    @Operation(
            summary = "Lower a warning's severity",
            description = "Moves the warning to a less serious severity and restates the wording to match. The new "
                    + "severity must be strictly lower than the current one, so a downgrade can never quietly "
                    + "escalate a warning. Replacement wording is required: the drafted text names the severity and "
                    + "carries the advice for it, and the original draft can never be rewritten, so changing the "
                    + "severity alone would leave end users reading the advice for a severity the warning no longer "
                    + "has. Any AI-written line is cleared, since it was generated for the old severity. A reason is "
                    + "required and is kept in the audit trail. The warning stays PENDING_REVIEW.")
    @ApiResponse(responseCode = "200", description = "Downgraded; the updated warning is returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = WarningResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing severity, wording or reason, an unknown severity, a "
            + "severity that is not lower than the current one, or the warning is not PENDING_REVIEW",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-11-09T14:22:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Cannot downgrade from MEDIUM to HIGH. A downgrade has to be less severe than the current severity.",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No warning with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public WarningResponse downgrade(
            @Parameter(description = "Warning id") @PathVariable UUID id,
            @Valid @RequestBody DowngradeWarningRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        return warningReviewService.downgrade(
                id, request.severity(), request.warningText(), request.reason(), actor.id());
    }

    @PostMapping("/{id}/suppress")
    @Operation(
            summary = "Suppress a warning",
            description = "Marks the warning SUPPRESSED so it is never sent. A reason is required, because this is a "
                    + "decision not to tell the public about a confirmed event, and it is kept in the audit trail. "
                    + "This is final: a suppressed warning is no longer PENDING_REVIEW, so it cannot be approved "
                    + "afterwards.")
    @ApiResponse(responseCode = "200", description = "Suppressed; the updated warning is returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = WarningResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing reason, or the warning is not PENDING_REVIEW",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No warning with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public WarningResponse suppress(
            @Parameter(description = "Warning id") @PathVariable UUID id,
            @Valid @RequestBody SuppressWarningRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        return warningReviewService.suppress(id, request.reason(), actor.id());
    }
}