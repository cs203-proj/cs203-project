package com.cs203.healthwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpatialScopeCalculatorTest {

    private static final Instant AT = Instant.parse("2026-10-10T04:00:00Z");
    private static final double Z = 3.0;
    private static final List<String> REGIONS = List.of("central", "north", "south", "east", "west");

    // median 50, scaledMad 10: above only when value > 80
    private static Map<String, BaselineSnapshot> baselines(String... regions) {
        Map<String, BaselineSnapshot> m = new HashMap<>();
        for (String r : regions) {
            m.put(r, new BaselineSnapshot(r, "pm25", 50.0, 10.0));
        }
        return m;
    }

    private static Map<String, BaselineSnapshot> allBaselines() {
        return baselines(REGIONS.toArray(String[]::new));
    }

    /** values in REGIONS order; a null value leaves that region out */
    private static Map<String, ReadingSnapshot> readings(Double... values) {
        Map<String, ReadingSnapshot> m = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                m.put(REGIONS.get(i), new ReadingSnapshot(REGIONS.get(i), "pm25", values[i], AT));
            }
        }
        return m;
    }

    private static ScopeResult scope(Map<String, ReadingSnapshot> r, Map<String, BaselineSnapshot> b) {
        return SpatialScopeCalculator.computeScope("central", r, b, Set.copyOf(REGIONS), Z);
    }

    @Test
    void onlyFlaggedAbove_isLocal() {
        ScopeResult r = scope(readings(120.0, 55.0, 60.0, 50.0, 70.0), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(r.regionsAbove()).containsExactly("central");
    }

    @Test
    void someAbove_isMultiRegion_sorted() {
        ScopeResult r = scope(readings(120.0, 55.0, 90.0, 50.0, 100.0), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(r.regionsAbove()).containsExactly("central", "south", "west");
    }

    @Test
    void allAbove_isIslandWide() {
        ScopeResult r = scope(readings(120.0, 100.0, 90.0, 85.0, 100.0), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.ISLAND_WIDE);
        assertThat(r.regionsAbove()).hasSize(5);
    }

    @Test
    void threeOfFiveReportingAllAbove_isMultiRegion() {
        // 3 of 5 report, all above: not every known region, so not island-wide
        ScopeResult r = scope(readings(120.0, 100.0, 90.0, null, null), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(r.regionsCompared()).containsExactly("central", "north", "south");
    }

    @Test
    void exactlyAtThreshold_isNotAbove() {
        // 80 gives z = 3.0 exactly; detection needs strictly more
        ScopeResult r = scope(readings(120.0, 80.0, 60.0, 50.0, 70.0), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(r.regionsAbove()).containsExactly("central");
    }

    @Test
    void fewerThanThreeRegions_isUnknown() {
        ScopeResult r = scope(readings(120.0, 100.0, null, null, null), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.UNKNOWN);
        assertThat(r.regionsAbove()).isEmpty();
    }

    @Test
    void emptyReadings_isUnknown() {
        assertThat(scope(Map.of(), allBaselines()).scope()).isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void emptyBaselines_isUnknown() {
        assertThat(scope(readings(120.0, 55.0, 60.0, 50.0, 70.0), Map.of()).scope()).isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void flaggedRegionWithoutBaseline_isUnknown() {
        Map<String, BaselineSnapshot> b = allBaselines();
        b.remove("central");
        assertThat(scope(readings(120.0, 100.0, 90.0, 85.0, 100.0), b).scope()).isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void flaggedRegionWithoutReading_isUnknown() {
        assertThat(scope(readings(null, 100.0, 90.0, 85.0, 100.0), allBaselines()).scope())
                .isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void fourOfFiveComparableAllAbove_isMultiRegion() {
        // west has no baseline: 4 of 5 comparable, all above -> multi-region (west is missing, not "not above")
        Map<String, BaselineSnapshot> b = allBaselines();
        b.remove("west");
        ScopeResult r = scope(readings(120.0, 100.0, 90.0, 85.0, 100.0), b);
        assertThat(r.scope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(r.regionsAbove()).doesNotContain("west");
        assertThat(r.regionsCompared()).containsExactly("central", "east", "north", "south");
    }

    @Test
    void tooFewComparableBecauseOfMissingBaselines_isUnknown() {
        assertThat(scope(readings(120.0, 100.0, 90.0, 85.0, 100.0), baselines("central", "north")).scope())
                .isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void unusableSpreadOrNonFiniteReading_isLeftOut() {
        Map<String, BaselineSnapshot> b = allBaselines();
        b.put("north", new BaselineSnapshot("north", "pm25", 50.0, 0.0)); // MAD 0
        ScopeResult r = scope(readings(120.0, 100.0, 90.0, Double.NaN, 60.0), b);
        // only central, south, west comparable
        assertThat(r.scope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(r.regionsAbove()).containsExactly("central", "south");
    }

    @Test
    void flaggedRegionNotAbove_isUnknown() {
        assertThat(scope(readings(60.0, 100.0, 90.0, 85.0, 100.0), allBaselines()).scope())
                .isEqualTo(SpatialScope.UNKNOWN);
    }

    @Test
    void fiveOfFiveAbove_isIslandWide_withSortedComparedList() {
        ScopeResult r = scope(readings(120.0, 100.0, 90.0, 85.0, 100.0), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.ISLAND_WIDE);
        assertThat(r.regionsCompared()).containsExactly("central", "east", "north", "south", "west");
    }

    @Test
    void localNeedsThreeComparable_andListsComparedRegions() {
        ScopeResult r = scope(readings(120.0, 55.0, 60.0, null, null), allBaselines());
        assertThat(r.scope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(r.regionsCompared()).containsExactly("central", "north", "south");
    }

    @Test
    void unknown_hasEmptyComparedList() {
        assertThat(scope(readings(120.0, 100.0, null, null, null), allBaselines()).regionsCompared()).isEmpty();
    }

    @Test
    void regionOutsideKnownSet_isIgnored() {
        Map<String, ReadingSnapshot> r = readings(120.0, 100.0, 90.0, 85.0, 100.0);
        r.put("mars", new ReadingSnapshot("mars", "pm25", 500.0, AT));
        Map<String, BaselineSnapshot> b = allBaselines();
        b.putAll(baselines("mars"));
        ScopeResult res = scope(r, b);
        assertThat(res.scope()).isEqualTo(SpatialScope.ISLAND_WIDE);
        assertThat(res.regionsCompared()).doesNotContain("mars");
    }
}
