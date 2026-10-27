package com.cs203.healthwatch.confidence;

import java.time.Duration;

/**
 * Confidence score for an investigated event (CG-16). Pure and deterministic: the same {@link ConfidenceInputs} always
 * give the same {@link ConfidenceResult}. It is computed here from measured inputs and never taken from the AI.
 * The formula, weights and bands are documented in docs/confidence-score.md; change them only together with a bump of
 * {@link #FORMULA_VERSION}, so stored scores stay explainable.
 */
public final class ConfidenceScorer {

    public static final int FORMULA_VERSION = 1;

    /** Readings are counted over this window before the event. */
    public static final Duration WINDOW = Duration.ofHours(24);
    /** Hourly feed over the window: this many readings is a full window. */
    public static final int EXPECTED_READINGS = 24;
    /** Data older than this scores 0 for freshness (3 missed hourly pulls). */
    public static final Duration MAX_STALENESS = Duration.ofHours(3);
    /** A deviation at or above this robust z-score scores 1. */
    public static final double DEVIATION_SATURATION = 10.0;

    public static final double WEIGHT_DEVIATION = 0.35;
    public static final double WEIGHT_READING_COUNT = 0.25;
    public static final double WEIGHT_STALENESS = 0.25;
    public static final double WEIGHT_AGREEMENT = 0.15;

    /** score below this is LOW */
    public static final double MEDIUM_FROM = 0.40;
    /** score at or above this is HIGH */
    public static final double HIGH_FROM = 0.70;

    private ConfidenceScorer() {
        // static utility, not instantiable
    }

    public static ConfidenceResult score(ConfidenceInputs in) {
        double deviation = clamp01(in.deviationSize() / DEVIATION_SATURATION);
        double count = clamp01((double) in.readingCount() / EXPECTED_READINGS);
        double fresh = 1.0 - clamp01((double) in.stalenessSeconds() / MAX_STALENESS.toSeconds());

        double weighted = WEIGHT_DEVIATION * deviation + WEIGHT_READING_COUNT * count + WEIGHT_STALENESS * fresh;
        double totalWeight = WEIGHT_DEVIATION + WEIGHT_READING_COUNT + WEIGHT_STALENESS;
        if (in.sourceAgreement() != null) {
            weighted += WEIGHT_AGREEMENT * clamp01(in.sourceAgreement());
            totalWeight += WEIGHT_AGREEMENT;
        }
        // an input that is not available (no comparable region) is left out and the other weights rescaled to sum to 1

        double score = round4(clamp01(weighted / totalWeight));
        return new ConfidenceResult(score, levelFor(score), FORMULA_VERSION);
    }

    public static ConfidenceLevel levelFor(double score) {
        if (score >= HIGH_FROM) {
            return ConfidenceLevel.HIGH;
        }
        return score >= MEDIUM_FROM ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    /** Clamps to [0, 1]; NaN and infinities can never leak into the score (NaN counts as 0). */
    private static double clamp01(double x) {
        if (Double.isNaN(x)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double round4(double x) {
        return Math.round(x * 10_000.0) / 10_000.0;
    }
}
