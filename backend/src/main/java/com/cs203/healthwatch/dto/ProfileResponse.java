package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.model.User;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ProfileResponse(
        @Schema(description = "Null until the user first sets a profile", example = "central", nullable = true,
                allowableValues = {"central", "north", "south", "east", "west"})
        String region,
        @Schema(nullable = true, example = "VULNERABLE",
                allowableValues = {"GENERAL_PUBLIC", "VULNERABLE", "HEALTHCARE_WORKER"})
        String audience,
        @ArraySchema(schema = @Schema(example = "ASTHMA",
                allowableValues = {"ASTHMA", "COPD", "HEART_DISEASE", "DIABETES", "PREGNANCY"}))
        List<String> healthConditions) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getRegion(), user.getAudience(),
                user.getHealthConditions().stream().sorted().toList());
    }
}
