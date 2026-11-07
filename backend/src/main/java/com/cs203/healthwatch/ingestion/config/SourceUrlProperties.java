package com.cs203.healthwatch.ingestion.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;

/**
 * Hosts an admin may point a data source url at (CG-58). The server fetches these urls itself, so only known
 * external feed hosts are allowed; anything else (localhost, private or cloud-metadata addresses, typos) is
 * rejected when the url is saved. Matching is exact on the host name.
 */
@ConfigurationProperties(prefix = "ingestion.sources")
@Validated
public record SourceUrlProperties(@NotEmpty List<String> allowedHosts) {}
