package com.cs203.healthwatch.ingestion.pm;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * The data.gov.sg PM2.5 feed and the regions ingested from it (CG-136). One feed is one source and one source_id, and regions are entries within it. 
 * Startup fails if a field is missing or the list is empty.
 *
 * @param name   data_sources.name; each source gets its own row, so readings carry a distinct source_id
 * @param url     endpoint to fetch
 * @param type    data_sources.type shared by every source; detection finds live readings by this
 * @param sources one entry per region to ingest
 */
@ConfigurationProperties(prefix = "pm")
@Validated
public record PmSourceProperties(
    @NotBlank String name, 
    @NotBlank String url, 
    @NotBlank String type, 
    @NotEmpty List<@Valid Region> regions) {

    /**
     * One region to ingest from this feed, e.g. central; stored in readings.region.
     *
     * @param region e.g. central; stored in readings.region
     */
    public record Region(@NotBlank String name) {}
}