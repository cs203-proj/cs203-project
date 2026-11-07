package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.ingestion.sources.DataSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A data source the system ingests from")
public record DataSourceResponse(
        @Schema(example = "5f1e2d3c-4b5a-4968-8776-655443322110") UUID id,
        @Schema(example = "datagovsg_pm25") String name,
        @Schema(description = "Signal type; detection groups readings by this", example = "air_quality") String type,
        @Schema(description = "Feed URL; null means the configured default is used",
                example = "https://api-open.data.gov.sg/v2/real-time/api/pm25") String url,
        @Schema(description = "Disabled sources are skipped by ingestion and backfill", example = "true")
        boolean enabled,
        @Schema(description = "True if the backend has ingestion code for this source. A registered source "
                + "with no ingestion code is stored but not pulled until that code is deployed.", example = "true")
        boolean ingestionAvailable,
        @Schema(example = "2026-09-24T03:15:00Z") Instant createdAt
) {
    public static DataSourceResponse from(DataSource source, boolean ingestionAvailable) {
        return new DataSourceResponse(source.getId(), source.getName(), source.getType(), source.getUrl(),
                source.isEnabled(), ingestionAvailable, source.getCreatedAt());
    }
}
