package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Full replacement of the caller's profile. Codes are checked in ProfileService; unknown codes give 400. */
public record ProfileRequest(
        @Schema(description = "Region code (lowercase)", example = "central",
                allowableValues = {"central", "north", "south", "east", "west"})
        String region,
        @Schema(description = "Warning audience (uppercase)", example = "VULNERABLE",
                allowableValues = {"GENERAL_PUBLIC", "VULNERABLE", "HEALTHCARE_WORKER"})
        String audience,
        @ArraySchema(arraySchema = @Schema(description = "Health condition codes; duplicates are ignored, empty is allowed"),
                schema = @Schema(example = "ASTHMA",
                        allowableValues = {"ASTHMA", "COPD", "HEART_DISEASE", "DIABETES", "PREGNANCY"}))
        List<String> healthConditions) {
}
