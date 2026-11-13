package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.warnings.WarningQueueRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The AI's verdict on a warning's event, shown to the admin in the review queue (CG-163). Not the same as
 * warnings.ai_line, which is per-user text for end users. CG-164 adds the cross-region scope.
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
        ConfidenceLevel confidenceLevel,
        @Schema(description = "How widely the spike shows up across regions, computed by the system (CG-164). UNKNOWN "
                + "when the comparison could not be made and for explanations written before it existed. Kept even "
                + "when status is UNAVAILABLE, because the system computes it, not the AI.", example = "MULTI_REGION")
        SpatialScope scope,
        @Schema(description = "Regions above the detection threshold; empty when scope is UNKNOWN",
                example = "[\"central\", \"north\"]")
        List<String> regionsAbove,
        @Schema(description = "Regions that took part in the comparison; empty when scope is UNKNOWN",
                example = "[\"central\", \"north\", \"south\"]")
        List<String> regionsCompared,
        @Schema(description = "The AI's plain-words account of the scope; null unless status is OK",
                example = "Central and north are above normal; 3 of 5 regions are reporting.")
        String scopeSummary
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
                    row.confidenceLevel(), scope(row), above(row), compared(row), null);
        }
        return new WarningAiSummary(AiStatus.OK, row.assessment(), row.likelyCause(), row.confidenceScore(),
                row.confidenceLevel(), scope(row), above(row), compared(row), row.scopeSummary());
    }

    // CG-164: a missing explanation row and a pre-V16 row both read as UNKNOWN with empty lists
    private static SpatialScope scope(WarningQueueRow row) {
        return row.scope() == null ? SpatialScope.UNKNOWN : row.scope();
    }

    private static List<String> above(WarningQueueRow row) {
        return row.regionsAbove() == null ? List.of() : row.regionsAbove();
    }

    private static List<String> compared(WarningQueueRow row) {
        return row.regionsCompared() == null ? List.of() : row.regionsCompared();
    }
}
