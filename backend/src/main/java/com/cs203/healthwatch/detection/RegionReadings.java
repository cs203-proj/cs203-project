package com.cs203.healthwatch.detection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the readings {@link SourceAgreement} compares (CG-16): for each region, its newest valid reading inside the
 * window {@code (eventTime - maxGap, eventTime]}. Readings that are null, not finite, too old or after the event are
 * ignored, so a region with nothing usable simply has no entry.
 */
public final class RegionReadings {

    private RegionReadings() {
        // static utility, not instantiable
    }

    public static List<ReadingSnapshot> newestPerRegionWithin(List<ReadingSnapshot> readings, Instant eventTime,
                                                              Duration maxGap) {
        Instant earliest = eventTime.minus(maxGap);
        Map<String, ReadingSnapshot> newest = new HashMap<>();
        for (ReadingSnapshot r : readings) {
            if (r == null || !Double.isFinite(r.value())) {
                continue;
            }
            if (!r.observedAt().isAfter(earliest) || r.observedAt().isAfter(eventTime)) {
                continue;
            }
            newest.merge(r.region(), r, (a, b) -> b.observedAt().isAfter(a.observedAt()) ? b : a);
        }
        return new ArrayList<>(newest.values());
    }
}
