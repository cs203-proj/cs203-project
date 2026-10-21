package com.cs203.healthwatch.confidence;

/** A computed confidence: the raw score (0..1), its band, and the formula version that produced it. */
public record ConfidenceResult(double score, ConfidenceLevel level, int formulaVersion) {}
