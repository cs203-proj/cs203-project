package com.cs203.healthwatch.ingestion.pm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "pm.source")
@Validated
public record PmSourceProperties(
    @NotBlank String url, 
    @NotBlank String name, 
    @NotBlank String type, 
    @NotBlank String region) {}