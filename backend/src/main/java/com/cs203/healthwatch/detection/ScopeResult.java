package com.cs203.healthwatch.detection;

import java.util.List;
import java.util.Map;

/**
 * @param regionsAbove    region codes above the detection threshold (flagged region included), sorted
 * @param regionsCompared region codes that took part in the comparison (had a reading and a usable baseline), sorted
 * @param regionZScores   robust z-score of each compared region, in the same sorted order
 * @param reason          why the scope is UNKNOWN (plain facts, for the AI prompt); null otherwise
 *                        All three of the first collections are empty when the scope is UNKNOWN.
 */
public record ScopeResult(SpatialScope scope, List<String> regionsAbove, List<String> regionsCompared,
                          Map<String, Double> regionZScores, String reason) {

    public static ScopeResult unknown(String reason) {
        return new ScopeResult(SpatialScope.UNKNOWN, List.of(), List.of(), Map.of(), reason);
    }
}
