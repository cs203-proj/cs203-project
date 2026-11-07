package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.validator.constraints.URL;

/**
 * Body of PUT /data-sources/{id}. Only the url can change: ingestion finds its source row by name and readings are
 * grouped by type, so renaming or retyping a live source would split its history. Send null to clear the url and
 * fall back to the configured default.
 */
@Schema(description = "Editable fields of a data source")
public record UpdateDataSourceRequest(
        @Schema(description = "Feed URL (https, on an allowed host); null falls back to the configured default",
                example = "https://api-open.data.gov.sg/v2/real-time/api/pm25")
        @URL(regexp = "^https://.*", message = "url must be a valid https URL")
        String url
) {
}
