package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.MyWarningListResponse;
import com.cs203.healthwatch.security.AuthenticatedUser;
import com.cs203.healthwatch.service.MyWarningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@Tag(name = "My warnings", description = "Warnings for the signed-in end user (any valid token)")
public class MeWarningController {

    private final MyWarningService myWarningService;

    public MeWarningController(MyWarningService myWarningService) {
        this.myWarningService = myWarningService;
    }

    @GetMapping("/warnings")
    @Operation(
            summary = "My active warnings",
            description = "Approved warnings that match your region and audience and were approved within the active "
                    + "window (24 hours by default). Pending, suppressed and expired warnings are never returned. "
                    + "If an admin edited a warning you see the edited text. Most severe first, then newest first. "
                    + "Each warning carries its confidence level and why it applies to you; whyThisApplies is built "
                    + "from your profile in code, and aiLine (if any) is the AI's and labelled as such. "
                    + "dataQualityNote appears only when the readings behind the warning were stale or incomplete. "
                    + "If your profile has no region or audience you get an empty list and a message.")
    @ApiResponse(responseCode = "200", description = "Active warnings (an empty list if none match)",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MyWarningListResponse.class),
                    examples = @ExampleObject(name = "One warning for a vulnerable user in the east", value = """
                            {
                              "items": [
                                {
                                  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                  "eventId": "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
                                  "severity": "HIGH",
                                  "region": "east",
                                  "audience": "VULNERABLE",
                                  "text": "PM2.5 (fine particle pollution) alert for the east region (high severity). Avoid strenuous outdoor activity and keep windows closed while levels stay high.",
                                  "approvedAt": "2026-10-14T02:40:00Z",
                                  "confidence": {
                                    "level": "MEDIUM",
                                    "meaning": "Moderate confidence: the signal looks real, but some checks are weaker. Treat it as a likely warning and check for updates."
                                  },
                                  "whyThisApplies": "You are in the east region and receive advice for vulnerable groups. Your profile lists asthma.",
                                  "aiLine": {
                                    "text": "Because you listed asthma, keep your reliever inhaler with you today.",
                                    "label": "AI-generated"
                                  },
                                  "dataQualityNote": "Based on partial data: 4 of 6 expected readings in the last 6 hours."
                                }
                              ],
                              "message": null
                            }
                            """)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    public MyWarningListResponse myWarnings(@AuthenticationPrincipal AuthenticatedUser user) {
        return myWarningService.forUser(user.id());
    }
}
