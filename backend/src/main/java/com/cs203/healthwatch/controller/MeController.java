package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.ProfileRequest;
import com.cs203.healthwatch.dto.ProfileResponse;
import com.cs203.healthwatch.security.AuthenticatedUser;
import com.cs203.healthwatch.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own account. The user always comes from the bearer token, never from a path or body id. */
@RestController
@RequestMapping("/me")
@Tag(name = "Me", description = "View and edit your own profile")
public class MeController {

    private final ProfileService profileService;

    public MeController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile")
    @Operation(summary = "Get my profile",
            description = "Region, audience and health condition codes. Fields are null / empty until first set.")
    @ApiResponse(responseCode = "200", description = "The caller's profile")
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token, or the account was deleted",
            content = @Content)
    public ProfileResponse getProfile(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return profileService.get(user.id());
    }

    @PutMapping("/profile")
    @Operation(summary = "Replace my profile",
            description = "Full replace. Allowed codes: region = central, north, south, east, west; "
                    + "audience = GENERAL_PUBLIC, VULNERABLE, HEALTHCARE_WORKER; healthConditions = the codes listed "
                    + "on the request schema. These are the same codes used for per-audience warnings.")
    @ApiResponse(responseCode = "200", description = "The updated profile")
    @ApiResponse(responseCode = "400", description = "Unknown region, audience or condition code; the message names the field and value",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    public ProfileResponse putProfile(
            @RequestBody ProfileRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        return profileService.update(user.id(), request);
    }
}
