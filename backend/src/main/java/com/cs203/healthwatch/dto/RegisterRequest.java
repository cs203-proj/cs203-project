package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /auth/register (CG-5 / CG-8).
 *
 * <p>There is deliberately no role field: registration always creates an end user, and an account that can reach
 * the admin endpoints can never be created from a public endpoint. Profile details (region, audience, health
 * conditions) are set afterwards through the profile endpoints (CG-6), not here.
 */
@Schema(description = "A new end-user account, with consent to the health-data notice")
public record RegisterRequest(
        @Schema(description = "Username to sign in with. Must not already be taken.", example = "alex.tan",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "username is required")
        @Size(min = 3, max = 50, message = "username must be between 3 and 50 characters")
        String username,

        @Schema(description = "Password, 8 to 72 characters. Stored only as a bcrypt hash. Capped at 72 because bcrypt ignores "
                + "everything after the first 72 bytes.",
                example = "correct-horse-battery", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password,

        @Schema(description = "Must be true. The account is not created without it.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean consent,

        @Schema(description = "Version of the consent notice the user was shown, from GET /consent",
                example = "1.0", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "consentVersion is required")
        String consentVersion
) {

    /** A record's default toString prints every field. Keep the password out of logs and error messages. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=<hidden>, consent=" + consent
                + ", consentVersion=" + consentVersion + "]";
    }
}
