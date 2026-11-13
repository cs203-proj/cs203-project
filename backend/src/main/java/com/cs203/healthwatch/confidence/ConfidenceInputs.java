package com.cs203.healthwatch.confidence;

/**
 * Everything the confidence score is computed from. These are stored with the event, so the score can always be
 * recomputed from them.
 *
 * @param stalenessSeconds seconds between the newest reading behind the event and the moment of scoring
 * @param readingCount     valid readings inside the scoring window (see {@link ConfidenceScorer#WINDOW})
 * @param deviationSize    the event's robust z-score (same value as the event's deviation)
 * @param sourceAgreement  share of the other regions that also show a spike, 0..1 (see {@code SourceAgreement});
 *                         {@code null} when no other region is comparable
 */
public record ConfidenceInputs(long stalenessSeconds, int readingCount, double deviationSize, Double sourceAgreement) {}
