package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /warnings/{id}/edit (CG-28). The AI's original text is never replaced: this is stored next to it as
 * editedText. As everywhere else, the actor is taken from the caller's token, never from the body.
 */
@Schema(description = "The admin's replacement wording for a drafted warning")
public record EditWarningRequest(
        @Schema(description = "Wording shown to end users in place of the AI draft once the warning is approved",
                example = "PM2.5 in the central region is high. Limit outdoor activity until further notice.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "warningText is required")
        String warningText
) {
}
