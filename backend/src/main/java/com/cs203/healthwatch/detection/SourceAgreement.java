package com.cs203.healthwatch.detection;

import java.util.List;
import java.util.Map;

/**
 * Source agreement for an event (CG-136 feeding CG-16): of the other regions that can be compared, the share whose
 * reading at the time of the event is also above the detection threshold. A spike seen in several regions is more
 * likely real than one seen in a single region.
 *
 * <p>A region can be compared only if it has a usable baseline and a valid reading close enough to the event (the
 * caller decides the window). Regions that cannot be compared are left out rather than counted as disagreeing, so
 * missing data never lowers confidence by itself; if no region can be compared the result is {@code null} and the
 * confidence score leaves the input out.
 */
public final class SourceAgreement {

    private SourceAgreement() {
        // static utility, not instantiable
    }

    /**
     * @param latestReadings  the latest reading of each other region near the event, at most one per region
     * @param baselines       baseline per region; regions without one (or with no usable spread) are skipped
     * @param zThreshold      the same robust z-score threshold detection uses
     * @return share of comparable regions also above the threshold, 0..1, or {@code null} if none is comparable
     */
    public static Double compute(List<ReadingSnapshot> latestReadings, Map<String, BaselineSnapshot> baselines,
                                 double zThreshold) {
        int compared = 0;
        int agreeing = 0;
        for (ReadingSnapshot r : latestReadings) {
            if (r == null || !Double.isFinite(r.value())) {
                continue;
            }
            BaselineSnapshot b = baselines.get(r.region());
            if (b == null || !b.hasUsableSpread()) {
                continue;
            }
            double z = (r.value() - b.median()) / b.scaledMad();
            if (!Double.isFinite(z)) {
                continue;
            }
            compared++;
            if (z > zThreshold) {
                agreeing++;
            }
        }
        return compared == 0 ? null : (double) agreeing / compared;
    }
}
