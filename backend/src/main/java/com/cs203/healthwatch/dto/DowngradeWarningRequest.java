package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /warnings/{id}/downgrade (CG-28).
 *
 * <p>Replacement wording is required, not optional. The drafted text states the severity in words and carries the
 * advice for that severity ("(high severity)… keep windows closed while levels stay high"), and the original draft
 * can never be rewritten. Lowering the severity without restating the wording would leave end users reading the
 * advice for a severity the warning no longer has, so the two change together or not at all.
 *
 * <p>A reason is required as well: lowering a public health warning is a judgement call, and the audit trail has to
 * record why it was made.
 */
@Schema(description = "The lower severity to move the warning to, the wording that goes with it, and why")
public record DowngradeWarningRequest(
        @Schema(description = "New severity, which must be less serious than the current one: LOW, MEDIUM, HIGH or "
                + "CRITICAL (case-insensitive)", example = "MEDIUM",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "severity is required")
        String severity,

        @Schema(description = "Replacement wording matching the new severity. Required, because the original draft "
                + "states the old severity and its advice.",
                example = "PM2.5 in the central region is elevated (medium severity). What you should do: cut down "
                        + "on prolonged or strenuous outdoor activity, especially if you have heart or lung "
                        + "conditions.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "warningText is required: a downgrade must restate the advice for the new severity")
        String warningText,

        @Schema(description = "Why the severity is being lowered. Recorded in the audit trail.",
                example = "Readings fell back towards the baseline within the hour.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "reason is required")
        String reason
) {
}
