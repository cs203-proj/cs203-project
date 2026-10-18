package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.detection.ReadingSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * How complete and fresh the readings behind an explanation are. Computed in code, never by the AI, so the
 * "stale or incomplete" statement is always present and always correct, whatever the AI writes.
 *
 * @param complete         true only if the latest reading is fresh and no expected reading is missing
 * @param note             one or two sentences an admin can read, e.g. "Based on partial data: ..."
 * @param readingsInWindow valid readings found in the lookback window
 * @param expectedReadings readings the feed should have produced in that window
 */
public record DataQuality(boolean complete, String note, int readingsInWindow, int expectedReadings) {

    /**
     * @param readings         valid readings, oldest first (may be empty)
     * @param referenceTime    when the analysis happens: now for live events, the detection time for replays
     * @param lookback         window ending at referenceTime that should be fully covered
     * @param expectedInterval how often the feed should report
     * @param staleAfter       latest reading older than this is stale
     */
    public static DataQuality assess(List<ReadingSnapshot> readings, Instant referenceTime, Duration lookback,
                                     Duration expectedInterval, Duration staleAfter) {
        int expected = (int) Math.max(1, lookback.toMinutes() / Math.max(1, expectedInterval.toMinutes()));
        String window = "the last " + describe(lookback);

        if (readings.isEmpty()) {
            return new DataQuality(false, "No valid readings in " + window + "; the explanation has no recent "
                    + "data to rest on.", 0, expected);
        }

        Instant windowStart = referenceTime.minus(lookback); // exclusive: a 6h hourly window holds 6 readings
        int inWindow = (int) readings.stream()
                .filter(r -> r.observedAt().isAfter(windowStart) && !r.observedAt().isAfter(referenceTime))
                .count();
        Instant latest = readings.get(readings.size() - 1).observedAt();
        Duration age = Duration.between(latest, referenceTime);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }

        boolean stale = age.compareTo(staleAfter) > 0;
        boolean partial = inWindow < expected;

        List<String> parts = new ArrayList<>();
        if (stale) {
            parts.add("Data is stale: the latest reading is " + describe(age) + " old (" + latest + ").");
        }
        if (partial) {
            parts.add("Based on partial data: " + inWindow + " of " + expected + " expected readings in " + window
                    + ".");
        }
        if (parts.isEmpty()) {
            parts.add("Based on complete data: " + inWindow + " of " + expected + " expected readings in " + window
                    + "; latest reading " + describe(age) + " before analysis.");
        }
        return new DataQuality(!stale && !partial, String.join(" ", parts), inWindow, expected);
    }

    /** "6 hours", "45 minutes", "2 hours 10 minutes". */
    static String describe(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours == 0) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        String h = hours + (hours == 1 ? " hour" : " hours");
        return minutes == 0 ? h : h + " " + minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
