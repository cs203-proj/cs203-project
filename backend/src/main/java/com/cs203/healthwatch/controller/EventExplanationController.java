package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.EventExplanationResponse;
import com.cs203.healthwatch.explanation.EventExplanationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@Tag(name = "Events", description = "Flagged events detected from live readings (admin only)")
public class EventExplanationController {

    private final EventExplanationService explanationService;

    public EventExplanationController(EventExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    @GetMapping("/{id}/explanation")
    @Operation(
            summary = "Get the AI explanation of an event",
            description = "Written automatically when the event is detected: the likely cause, whether it looks "
                    + "like noise or a genuine trend, the key evidence and the assumptions. dataQualityNote states "
                    + "whether the readings were stale or incomplete. If the AI call failed, status is FAILED and "
                    + "error says why; the event itself is unaffected.")
    @ApiResponse(responseCode = "200", description = "Explanation returned",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EventExplanationResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "eventId": "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
                              "status": "OK",
                              "assessment": "GENUINE_TREND",
                              "likelyCause": "Consistent with regional haze: a gradual, sustained rise.",
                              "explanation": "PM2.5 has stayed far above its usual level for three hours in a row. A z-score of 6.1 means the reading is about six typical spreads above normal. The rise is steady rather than a single jump, which points to a real change rather than a sensor fault.",
                              "evidence": ["Last 3 readings: 58, 64, 71 against a median of 18", "Readings rose steadily over 5 hours"],
                              "assumptions": ["The station is reporting correctly", "The 30-day baseline reflects normal conditions"],
                              "dataComplete": true,
                              "dataQualityNote": "Based on complete data: 6 of 6 expected readings in the last 6 hours; latest reading 12 minutes before analysis.",
                              "model": "gemini-3.8-flash",
                              "error": null,
                              "createdAt": "2026-10-18T09:12:03Z"
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    @ApiResponse(responseCode = "404", description = "No event with this id, or no explanation yet",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class)))
    public EventExplanationResponse explanation(
            @Parameter(description = "Event id", example = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")
            @PathVariable UUID id) {
        return EventExplanationResponse.from(explanationService.getForEvent(id));
    }
}
