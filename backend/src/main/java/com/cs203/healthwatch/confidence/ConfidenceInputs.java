package com.cs203.healthwatch.confidence;

/**
 * Everything the confidence score is computed from. These are stored with the event, so the score can always be
 * recomputed from them.
 *
 * @param stalenessSeconds seconds between the newest reading behind the event and the moment of scoring
 * @param readingCount     valid readings inside the scoring window (see {@link ConfidenceScorer#WINDOW})
 * @param deviationSize    the event's robust z-score (same value as the event's deviation)
 * @param sourceAgreement  share of sources that agree with the flag, 0..1; {@code null} while only one source exists
 *                         (until CG-136 lands)
 */
public record ConfidenceInputs(long stalenessSeconds, int readingCount, double deviationSize, Double sourceAgreement) {}
