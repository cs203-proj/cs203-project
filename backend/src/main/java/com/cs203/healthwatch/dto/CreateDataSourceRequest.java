package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/** Body of POST /data-sources. New sources start disabled; enable them with PATCH /data-sources/{id}/enabled. */
@Schema(description = "A data source to register")
public record CreateDataSourceRequest(
        @Schema(example = "datagovsg_pm25", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "name is required") @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Schema(example = "air_quality", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "type is required") @Size(max = 50, message = "type must be at most 50 characters")
        String type,

        @Schema(description = "Feed URL (https, on an allowed host); optional",
                example = "https://api-open.data.gov.sg/v2/real-time/api/pm25")
        @URL(regexp = "^https://.*", message = "url must be a valid https URL")
        String url
) {
}
