package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /data-sources/{id}/enabled. */
@Schema(description = "Enable or disable a data source")
public record SetDataSourceEnabledRequest(
        @Schema(example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "body missing or enabled not true/false")
        Boolean enabled
) {
}
