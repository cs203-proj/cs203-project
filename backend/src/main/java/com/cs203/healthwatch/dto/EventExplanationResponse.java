package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "AI explanation of a flagged event. When status is FAILED, the AI fields are null and error "
        + "says why; the data-quality fields are always filled.")
public record EventExplanationResponse(
        UUID eventId,
        @Schema(example = "OK") ExplanationStatus status,
        @Schema(description = "AI verdict: GENUINE_TREND, LIKELY_NOISE or UNCERTAIN", example = "GENUINE_TREND")
        Assessment assessment,
        @Schema(example = "Consistent with regional haze: a gradual, sustained rise across consecutive readings.")
        String likelyCause,
        String explanation,
        List<String> evidence,
        List<String> assumptions,
        @Schema(description = "False if the readings were stale or incomplete (computed by the system, not the AI)")
        boolean dataComplete,
        @Schema(example = "Based on partial data: 4 of 6 expected readings in the last 6 hours.")
        String dataQualityNote,
        @Schema(example = "openai/gpt-oss-120b") String model,
        @Schema(description = "Why the AI call failed; null when status is OK") String error,
        Instant createdAt,
        @Schema(description = "How widely the spike shows up across regions at the same time. Computed by the "
                + "system, not the AI. UNKNOWN when the comparison could not be made, and for older explanations.",
                example = "MULTI_REGION")
        SpatialScope scope,
        @Schema(description = "Regions above the detection threshold, flagged region included; empty when UNKNOWN",
                example = "[\"central\", \"north\"]")
        List<String> regionsAbove,
        @Schema(description = "Regions that took part in the comparison (had a reading and a baseline); empty when "
                + "UNKNOWN", example = "[\"central\", \"north\", \"south\"]")
        List<String> regionsCompared,
        @Schema(description = "The AI's plain-words account of the scope; null when the AI call failed or for "
                + "older explanations", example = "Central and north are above normal; 3 of 5 regions are reporting.")
        String scopeSummary
) {
    public static EventExplanationResponse from(EventExplanation e) {
        return new EventExplanationResponse(e.getEventId(), e.getStatus(), e.getAssessment(), e.getLikelyCause(),
                e.getExplanation(), e.getEvidence(), e.getAssumptions(), e.isDataComplete(), e.getDataQualityNote(),
                e.getModel(), e.getError(), e.getCreatedAt(), e.scopeOrUnknown(),
                orEmpty(e.getRegionsAbove()), orEmpty(e.getRegionsCompared()), e.getScopeSummary());
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
