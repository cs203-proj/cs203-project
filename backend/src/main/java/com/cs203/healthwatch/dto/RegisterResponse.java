package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.model.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of a successful POST /auth/register (CG-5). No token is returned: the client logs in through
 * POST /auth/login like any other sign-in, so there is one place where tokens are issued.
 */
@Schema(description = "The newly created end-user account")
public record RegisterResponse(
        @Schema(example = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d") UUID id,
        @Schema(example = "alex.tan") String username,
        @Schema(description = "Always the end-user role; registration cannot create an admin", example = "viewer")
        String role,
        @Schema(description = "Version of the consent notice accepted", example = "1.0") String consentVersion,
        @Schema(description = "When consent was given", example = "2026-11-09T18:11:00Z") Instant consentedAt
) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername(), user.getRole(),
                user.getConsentVersion(), user.getConsentedAt());
    }
}
