package com.cs203.healthwatch.detection;

import java.time.Instant;

public record ReadingSnapshot(String region, String signalType, double value, Instant observedAt) {}