package com.cs203.healthwatch.detection;

public record BaselineSnapshot(String region, String signalType, double median, double scaledMad) {}