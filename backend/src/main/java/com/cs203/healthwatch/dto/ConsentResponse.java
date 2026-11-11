package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of GET /consent (CG-8). The client shows {@code text} at sign-up and sends {@code version} back in the
 * registration request, so what the user agreed to is recorded exactly.
 */
@Schema(description = "The consent notice a user accepts at sign-up")
public record ConsentResponse(
        @Schema(description = "Version of this notice. Send it back in POST /auth/register.", example = "1.0")
        String version,

        @Schema(description = "The notice shown to the user before they register")
        String text
) {
}
