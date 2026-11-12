package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.warnings.WarningQueueRow;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The AI's verdict on a warning's event, shown to the admin in the review queue (CG-163). Not the same as
 * warnings.ai_line, which is per-user text for end users. CG-164 will add scope and regionsAbove here.
 */
@Schema(description = "AI summary of the warning's event, for triage in the review queue. When status is "
        + "UNAVAILABLE, assessment and likelyCause are null; confidence is still filled if the event has one.")
public record WarningAiSummary(
        @Schema(description = "OK: the AI explanation exists. UNAVAILABLE: the AI call failed or no explanation "
                + "row exists for the event.", example = "OK")
        AiStatus status,
        @Schema(description = "The AI's verdict; null unless status is OK", example = "GENUINE_TREND")
        Assessment assessment,
        @Schema(description = "The AI's likely cause; null unless status is OK",
                example = "Consistent with regional haze: a gradual, sustained rise across consecutive readings.")
        String likelyCause,
        @Schema(description = "Event confidence score from 0.0 to 1.0 inclusive (ConfidenceScorer clamps it); "
                + "null on events scored before confidence existed",
                example = "0.7425")
        Double confidenceScore,
        @Schema(description = "Band of the score: LOW below 0.40, MEDIUM from 0.40, HIGH from 0.70; null when "
                + "confidenceScore is null", example = "HIGH")
        ConfidenceLevel confidenceLevel
) {
    public enum AiStatus { OK, UNAVAILABLE }

    /**
     * CG-166: OK only for a stored OK explanation. A FAILED explanation and a missing row are both UNAVAILABLE, with
     * the text fields null (a FAILED row has no usable assessment or cause). Confidence belongs to the event, not
     * the explanation, so it is kept either way.
     */
    public static WarningAiSummary of(WarningQueueRow row) {
        if (row.explanationStatus() != ExplanationStatus.OK) {
            return new WarningAiSummary(AiStatus.UNAVAILABLE, null, null, row.confidenceScore(),
                    row.confidenceLevel());
        }
        return new WarningAiSummary(AiStatus.OK, row.assessment(), row.likelyCause(), row.confidenceScore(),
                row.confidenceLevel());
    }
}
