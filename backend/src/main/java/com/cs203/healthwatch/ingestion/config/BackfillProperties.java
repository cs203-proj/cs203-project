package com.cs203.healthwatch.ingestion.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Settings for the PM2.5 backfill (CG-136).
 *
 * @param delayBetweenDays pause between days, so rapid requests don't hit data.gov.sg's rate limit
 * @param maxDays          largest range one request may cover; keeps a synchronous call to a bounded time
 * @param maxPagesPerDay   guard against a day whose paginationToken never runs out
 */
@ConfigurationProperties(prefix = "ingestion.backfill")
@Validated
public record BackfillProperties(
        @NotNull Duration delayBetweenDays,
        @Min(1) int maxDays,
        @Min(1) int maxPagesPerDay) {}
