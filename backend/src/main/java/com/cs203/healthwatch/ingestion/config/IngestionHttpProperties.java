package com.cs203.healthwatch.ingestion.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@ConfigurationProperties(prefix = "ingestion.http")
@Validated
public record IngestionHttpProperties(
        @NotNull Duration connectTimeout, 
        @NotNull Duration readTimeout,
        @Min(1) int maxAttempts, 
        @NotNull Duration initialBackoff) {}
