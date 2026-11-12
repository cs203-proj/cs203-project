package com.cs203.healthwatch.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.detection.ScopeResult;
import com.cs203.healthwatch.detection.SpatialScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CG-172: the scope facts reach the prompt, and the rules forbid guessing. */
class ExplanationPromptTest {

    private static final List<String> KNOWN = List.of("central", "east", "north", "south", "west");

    private static String facts(ScopeResult scope) {
        var ctx = new ExplanationContext(UUID.randomUUID(), "central", "pm25", Instant.parse("2026-10-18T03:00:00Z"),
                6.12, false, 18.0, 5.0, 3.0, 3, List.of(), new DataQuality(true, "complete", 6, 6), scope, KNOWN);
        return ExplanationPrompt.facts(ctx);
    }

    @Test
    void multiRegion_includesScopeListsCoverageAndEachZScore() {
        Map<String, Double> z = new TreeMap<>(Map.of("central", 6.1, "north", 3.4, "south", 0.5));
        String f = facts(new ScopeResult(SpatialScope.MULTI_REGION, List.of("central", "north"),
                List.of("central", "north", "south"), z, null));

        assertThat(f).contains("Scope: MULTI_REGION")
                .contains("Regions reporting: 3 of 5 (central, north, south)")
                .contains("Regions above the threshold: central, north")
                .contains("central: z=6.10").contains("north: z=3.40").contains("south: z=0.50")
                .contains("Known regions: central, east, north, south, west");
    }

    @Test
    void unknown_givesTheReason_andNoRegionLists() {
        String f = facts(ScopeResult.unknown("only 2 of 5 regions had a usable reading and baseline"));

        assertThat(f).contains("Scope: UNKNOWN")
                .contains("Why unknown: only 2 of 5 regions had a usable reading and baseline")
                .doesNotContain("Regions above the threshold");
    }

    @Test
    void systemRules_forbidRecomputingAndInventing() {
        assertThat(ExplanationPrompt.SYSTEM_INSTRUCTION)
                .contains("never upgrade, downgrade or recompute")
                .contains("Name no region and quote no number that is not in the facts")
                .contains("3 of 5 regions reporting")
                .contains("If the scope is UNKNOWN, say plainly")
                .contains("scopeSummary");
    }
}
