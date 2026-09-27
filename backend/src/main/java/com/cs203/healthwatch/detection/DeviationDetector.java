package com.cs203.healthwatch.detection;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class DeviationDetector {

    private DeviationDetector() {
        // static utility, not instantiable
    }

    /**
     * Flags a sustained high deviation: the last N readings must all be above the
     * threshold, be valid numbers, and be spaced no further apart than maxGap.
     *
     * @param baseline            may be empty; caller handles the "no baseline" case
     * @param recentReadings      readings for this region+signal, ordered oldest to newest
     * @param zThreshold          robust z-score a reading must exceed (high direction only)
     * @param consecutiveRequired how many consecutive readings must breach (must be >= 1)
     * @param maxGap              largest allowed time between consecutive readings
     * @return a Deviation if the sustained-spike condition is met, otherwise empty
     */
    public static Optional<Deviation> detect(
            Optional<BaselineSnapshot> baseline,
            List<ReadingSnapshot> recentReadings,
            double zThreshold,
            int consecutiveRequired,
            Duration maxGap
    ) {
        if (consecutiveRequired < 1) {
            throw new IllegalArgumentException("consecutiveRequired must be at least 1");
        }
        if (baseline.isEmpty()) {
            return Optional.empty(); // caller logs "no baseline, skipping"
        }
        if (recentReadings.size() < consecutiveRequired) {
            return Optional.empty(); // not enough data yet
        }

        BaselineSnapshot b = baseline.get();
        if (b.scaledMad() <= 0) {
            return Optional.empty(); // flat history: z-scores are meaningless
        }

        List<ReadingSnapshot> lastN = recentReadings.subList(
                recentReadings.size() - consecutiveRequired, recentReadings.size());

        double maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < lastN.size(); i++) {
            ReadingSnapshot r = lastN.get(i);

            if (r == null || Double.isNaN(r.value())) {
                return Optional.empty(); // invalid reading: no event
            }

            if (i > 0) {
                Duration step = Duration.between(lastN.get(i - 1).observedAt(), r.observedAt());
                if (step.compareTo(maxGap) > 0) {
                    return Optional.empty(); // gap in readings: not consecutive
                }
            }

            double z = (r.value() - b.median()) / b.scaledMad();
            if (z <= zThreshold) {
                return Optional.empty(); // at least one reading not breaching: no event
            }
            maxZ = Math.max(maxZ, z);
        }

        ReadingSnapshot latest = lastN.get(lastN.size() - 1);
        return Optional.of(new Deviation(
                latest.region(),
                latest.signalType(),
                latest.observedAt(),
                maxZ
        ));
    }
}
