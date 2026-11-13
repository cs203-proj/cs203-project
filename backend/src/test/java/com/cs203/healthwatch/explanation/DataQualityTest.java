package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.hourly;
import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.detection.ReadingSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The stale / incomplete-data statement is computed in code, so it is correct whatever the AI writes. */
class DataQualityTest {

    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private static final Duration LOOKBACK = Duration.ofHours(6);
    private static final Duration HOURLY = Duration.ofMinutes(60);
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    private static DataQuality assess(List<ReadingSnapshot> readings) {
        return DataQuality.assess(readings, NOW, LOOKBACK, HOURLY, STALE_AFTER);
    }

    @Test
    void fullFreshWindow_isComplete() {
        DataQuality q = assess(hourly(NOW.minus(Duration.ofMinutes(12)), 18, 20, 25, 40, 55, 70));

        assertThat(q.complete()).isTrue();
        assertThat(q.readingsInWindow()).isEqualTo(6);
        assertThat(q.expectedReadings()).isEqualTo(6);
        assertThat(q.note()).startsWith("Based on complete data: 6 of 6 expected readings in the last 6 hours")
                .contains("12 minutes before analysis");
    }

    @Test
    void missingReadings_saysPartialWithCounts() {
        List<ReadingSnapshot> readings = new ArrayList<>(hourly(NOW.minus(Duration.ofMinutes(10)), 18, 20, 25, 40,
                55, 70));
        readings.remove(1);
        readings.remove(1);

        DataQuality q = assess(readings);

        assertThat(q.complete()).isFalse();
        assertThat(q.note()).isEqualTo("Based on partial data: 4 of 6 expected readings in the last 6 hours.");
    }

    @Test
    void oldLatestReading_saysStaleWithAgeAndTime() {
        Instant last = NOW.minus(Duration.ofHours(3).plusMinutes(5));

        DataQuality q = assess(hourly(last, 40, 55, 70));

        assertThat(q.complete()).isFalse();
        assertThat(q.note()).contains("Data is stale: the latest reading is 3 hours 5 minutes old (" + last + ")")
                .contains("Based on partial data");
    }

    @Test
    void noReadings_isIncompleteAndSaysSo() {
        DataQuality q = assess(List.of());

        assertThat(q.complete()).isFalse();
        assertThat(q.readingsInWindow()).isZero();
        assertThat(q.note()).startsWith("No valid readings in the last 6 hours");
    }

    @Test
    void readingsOutsideWindow_doNotCountTowardsCompleteness() {
        // 12 hourly readings: only the last 6 fall in the 6-hour window
        DataQuality q = assess(hourly(NOW, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));

        assertThat(q.readingsInWindow()).isEqualTo(6);
        assertThat(q.complete()).isTrue();
    }

    @Test
    void nonFiniteReadings_areNotCounted() {
        DataQuality q = assess(hourly(NOW.minus(Duration.ofMinutes(10)), 18, Double.NaN, 25, 40, 55, 70));

        assertThat(q.readingsInWindow()).isEqualTo(5);
        assertThat(q.complete()).isFalse();
        assertThat(q.note()).contains("5 of 6");
    }

    @Test
    void describe_readsNaturally() {
        assertThat(DataQuality.describe(Duration.ofMinutes(1))).isEqualTo("1 minute");
        assertThat(DataQuality.describe(Duration.ofMinutes(45))).isEqualTo("45 minutes");
        assertThat(DataQuality.describe(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(DataQuality.describe(Duration.ofMinutes(130))).isEqualTo("2 hours 10 minutes");
    }
}
