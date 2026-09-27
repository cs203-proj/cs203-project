package com.cs203.healthwatch.detection;

import java.time.Instant;

public record Deviation(String region, String signalType, Instant timestamp, double deviationSize) {}