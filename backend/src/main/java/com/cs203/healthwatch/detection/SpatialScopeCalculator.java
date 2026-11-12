package com.cs203.healthwatch.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local spike or island-wide haze (CG-170). Pure: no Spring, no DB. Uses the detection pipeline's own test, a robust
 * z-score strictly above {@code app.detection.z-threshold} (see {@link DeviationDetector}, {@link SourceAgreement}).
 *
 * <p>The caller picks "the same time" readings with {@link RegionReadings#newestPerRegionWithin}, i.e. each region's
 * newest reading in {@code (eventTime - maxGap, eventTime]}. As in SourceAgreement, a region is only compared when it
 * has a finite reading and a baseline with usable spread; the others are left out, not counted as "not above".
 *
 * <p>Final rules, over the comparable regions (regions outside {@code knownRegions} are ignored):
 * <ul>
 *   <li>UNKNOWN: the flagged region has no usable baseline, or fewer than {@value #MIN_REGIONS} regions are
 *       comparable, or the flagged region is not itself above the threshold (including: it has no reading).</li>
 *   <li>LOCAL: only the flagged region is above.</li>
 *   <li>ISLAND_WIDE: every known region is comparable AND above.</li>
 *   <li>MULTI_REGION: 2 or more regions above, but not ISLAND_WIDE (also when every reporting region is above but
 *       some known regions are missing).</li>
 * </ul>
 *
 * <p>Baselines: callers pass whatever {@code BaselineRepository.latestBaseline} returns, i.e. the newest computed
 * baseline at the time of the call. A replay therefore compares against current baselines, not the baselines that
 * existed at the replayed event time (same as replay detection itself). Not changed here.
 */
public final class SpatialScopeCalculator {

    /** Fewer comparable regions than this cannot tell local from island-wide. */
    static final int MIN_REGIONS = 3;

    private SpatialScopeCalculator() {
        // static utility, not instantiable
    }

    public static ScopeResult computeScope(String flaggedRegion, Map<String, ReadingSnapshot> readingsAtSameTime,
                                           Map<String, BaselineSnapshot> baselines, Set<String> knownRegions,
                                           double zThreshold) {
        ScopeResult unknown = new ScopeResult(SpatialScope.UNKNOWN, List.of(), List.of());
        BaselineSnapshot flaggedBaseline = baselines.get(flaggedRegion);
        if (flaggedBaseline == null || !flaggedBaseline.hasUsableSpread()) {
            return unknown;
        }
        List<String> comparedRegions = new ArrayList<>();
        boolean flaggedAbove = false;
        List<String> above = new ArrayList<>();
        for (Map.Entry<String, ReadingSnapshot> e : readingsAtSameTime.entrySet()) {
            ReadingSnapshot r = e.getValue();
            BaselineSnapshot b = baselines.get(e.getKey());
            if (!knownRegions.contains(e.getKey()) || r == null || !Double.isFinite(r.value()) || b == null || !b.hasUsableSpread()) {
                continue;
            }
            double z = (r.value() - b.median()) / b.scaledMad();
            if (!Double.isFinite(z)) {
                continue;
            }
            comparedRegions.add(e.getKey());
            if (z > zThreshold) {
                above.add(e.getKey());
                flaggedAbove |= e.getKey().equals(flaggedRegion);
            }
        }
        // ponytail: if the flagged region itself is not above, the data contradicts the event, so we say UNKNOWN
        if (comparedRegions.size() < MIN_REGIONS || !flaggedAbove) {
            return unknown;
        }
        above.sort(null);
        comparedRegions.sort(null);
        boolean everyKnownRegionAbove = above.size() == knownRegions.size(); // above is a subset of known
        SpatialScope scope = above.size() == 1 ? SpatialScope.LOCAL
                : everyKnownRegionAbove ? SpatialScope.ISLAND_WIDE : SpatialScope.MULTI_REGION;
        return new ScopeResult(scope, List.copyOf(above), List.copyOf(comparedRegions));
    }
}
