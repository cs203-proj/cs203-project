package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.AuditEntryResponse;
import com.cs203.healthwatch.dto.EventListResponse;
import com.cs203.healthwatch.dto.EventResponse;
import com.cs203.healthwatch.dto.UpdateEventStatusRequest;
import com.cs203.healthwatch.security.AuthenticatedUser;
import com.cs203.healthwatch.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Flagged events detected from live readings (admin only)")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(
            summary = "List flagged events",
            description = "Returns flagged events sorted by deviation size, largest first, so the most serious "
                    + "appear at the top. Optionally filter by status. Results are paged with limit and offset.")
    @ApiResponse(responseCode = "200", description = "Events returned (an empty list if none match)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EventListResponse.class),
                    examples = @ExampleObject(name = "Two flagged events", value = """
                            {
                              "items": [
                                {
                                  "id": "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
                                  "timestamp": "2026-09-24T03:15:00Z",
                                  "signalType": "AQI",
                                  "region": "Singapore",
                                  "status": "NEW",
                                  "deviationSize": 4.2
                                },
                                {
                                  "id": "8d1e4a72-5c3b-49f0-a6e2-7b9c0d1e2f34",
                                  "timestamp": "2026-09-23T22:40:00Z",
                                  "signalType": "AQI",
                                  "region": "Singapore",
                                  "status": "UNDER_REVIEW",
                                  "deviationSize": 3.1
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
                              "timestamp": "2026-09-24T03:20:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Unknown status 'FOO'. Valid values: NEW, UNDER_REVIEW, CONFIRMED, DISMISSED",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public EventListResponse listEvents(
            @Parameter(description = "Only return events with this status: NEW, UNDER_REVIEW, CONFIRMED or DISMISSED "
                    + "(case-insensitive). Omit to return all statuses.", example = "NEW")
            @RequestParam(required = false) String status,
            @Parameter(description = "Maximum number of events to return (1-200)", example = "50")
            @RequestParam(defaultValue = "" + EventService.DEFAULT_LIMIT) int limit,
            @Parameter(description = "Number of events to skip, for paging", example = "0")
            @RequestParam(defaultValue = "0") long offset) {
        return eventService.list(status, limit, offset);
    }

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "Change an event's status",
            description = "Moves an event through its lifecycle and records the change in the audit log, in one "
                    + "transaction. Allowed moves: NEW → UNDER_REVIEW or DISMISSED; UNDER_REVIEW → CONFIRMED or "
                    + "DISMISSED; DISMISSED → UNDER_REVIEW (reopen). CONFIRMED is final. The actor recorded is "
                    + "always the user in the bearer token; any actor sent in the body is ignored.")
    @ApiResponse(responseCode = "200", description = "Status changed; the updated event is returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EventResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "id": "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
                              "timestamp": "2026-09-24T03:15:00Z",
                              "signalType": "AQI",
                              "region": "Singapore",
                              "status": "UNDER_REVIEW",
                              "deviationSize": 4.2
                            }
                            """)))
    @ApiResponse(responseCode = "400", description = "Unknown status, missing status, or a transition that is "
            + "not allowed",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-09-24T04:05:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Cannot change status from NEW to CONFIRMED. Allowed from NEW: UNDER_REVIEW, DISMISSED",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public EventResponse changeStatus(
            @Parameter(description = "Event id", example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEventStatusRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        return eventService.changeStatus(id, request.status(), actor.id());
    }

    @GetMapping("/{id}/audit")
    @Operation(
            summary = "Get an event's audit trail",
            description = "Every status change on the event, oldest first, with who made it and when. Audit "
                    + "entries are append-only and can never be edited or deleted.")
    @ApiResponse(responseCode = "200", description = "Audit entries (an empty list if the status never changed)",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = AuditEntryResponse.class)),
                    examples = @ExampleObject(value = """
                            [
                              {
                                "id": 17,
                                "actorId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                                "actorUsername": "admin",
                                "oldStatus": "NEW",
                                "newStatus": "UNDER_REVIEW",
                                "changedAt": "2026-09-24T04:02:11Z"
                              },
                              {
                                "id": 21,
                                "actorId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                                "actorUsername": "admin",
                                "oldStatus": "UNDER_REVIEW",
                                "newStatus": "CONFIRMED",
                                "changedAt": "2026-09-24T06:47:30Z"
                              }
                            ]
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No event with this id",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public List<AuditEntryResponse> auditTrail(
            @Parameter(description = "Event id", example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")
            @PathVariable UUID id) {
        return eventService.auditTrail(id);
    }
}
