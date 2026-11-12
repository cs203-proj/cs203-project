package com.cs203.healthwatch.detection;

import java.util.List;

/**
 * @param regionsAbove    region codes above the detection threshold (flagged region included), sorted
 * @param regionsCompared region codes that took part in the comparison (had a reading and a usable baseline), sorted
 *                        Both are empty when the scope is UNKNOWN.
 */
public record ScopeResult(SpatialScope scope, List<String> regionsAbove, List<String> regionsCompared) {}
