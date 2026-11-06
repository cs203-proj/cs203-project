package com.cs203.healthwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionReadingsTest {

    private static final Instant EVENT = Instant.parse("2026-10-10T04:00:00Z");
    private static final Duration MAX_GAP = Duration.ofMinutes(90);

    private static ReadingSnapshot reading(String region, double value, long minutesBeforeEvent) {
        return new ReadingSnapshot(region, "pm25", value, EVENT.minus(Duration.ofMinutes(minutesBeforeEvent)));
    }

    private static List<ReadingSnapshot> pick(ReadingSnapshot... readings) {
        return RegionReadings.newestPerRegionWithin(Arrays.asList(readings), EVENT, MAX_GAP);
    }

    @Test
    void keepsOnlyTheNewestReadingPerRegion() {
        var picked = pick(reading("north", 130, 60), reading("north", 55, 5), reading("south", 70, 30));

        assertThat(picked).hasSize(2);
        assertThat(picked).anyMatch(r -> r.region().equals("north") && r.value() == 55);
        assertThat(picked).anyMatch(r -> r.region().equals("south") && r.value() == 70);
    }

    @Test
    void windowIsExclusiveAtTheStartAndInclusiveAtTheEvent() {
        assertThat(pick(reading("north", 1, 90))).isEmpty();  // exactly maxGap before: out
        assertThat(pick(reading("north", 1, 89))).hasSize(1);
        assertThat(pick(reading("north", 1, 0))).hasSize(1);  // at the event: in
        assertThat(pick(reading("north", 1, -1))).isEmpty();  // after the event: out
    }

    @Test
    void ignoresNullAndNonFiniteReadings() {
        assertThat(pick(null, reading("north", Double.NaN, 5), reading("south", Double.NEGATIVE_INFINITY, 5)))
                .isEmpty();
    }

    @Test
    void anInvalidNewerReadingDoesNotHideAValidOlderOne() {
        var picked = pick(reading("north", 70, 30), reading("north", Double.NaN, 5));
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).value()).isEqualTo(70);
    }

    @Test
    void emptyInput_givesEmptyList() {
        assertThat(pick()).isEmpty();
    }
}
