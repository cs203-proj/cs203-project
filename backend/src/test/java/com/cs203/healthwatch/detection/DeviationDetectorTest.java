package com.cs203.healthwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviationDetectorTest {

    private static final double Z = 3.0;
    private static final int N = 3;
    private static final Duration MAX_GAP = Duration.ofMinutes(90);
    private static final Instant START = Instant.parse("2026-09-03T00:00:00Z");

    // median 50, scaledMad 10: a reading breaches 3 sigma when it is above 80
    private final Optional<BaselineSnapshot> baseline =
            Optional.of(new BaselineSnapshot("north", "AQI", 50.0, 10.0));

    private static ReadingSnapshot at(int hour, double value) {
        return new ReadingSnapshot("north", "AQI", value, START.plus(Duration.ofHours(hour)));
    }

    private Optional<Deviation> detect(List<ReadingSnapshot> readings) {
        return DeviationDetector.detect(baseline, readings, Z, N, MAX_GAP);
    }

    @Test
    void belowThreshold_noEvent() {
        assertThat(detect(List.of(at(0, 55), at(1, 58), at(2, 60)))).isEmpty();
    }

    @Test
    void exactlyAtThreshold_noEvent() {
        assertThat(detect(List.of(at(0, 80), at(1, 80), at(2, 80)))).isEmpty();
    }

    @Test
    void singleSpike_noEvent() {
        assertThat(detect(List.of(at(0, 55), at(1, 58), at(2, 120)))).isEmpty();
    }

    @Test
    void sustainedSpike_event() {
        var result = detect(List.of(at(0, 120), at(1, 125), at(2, 130)));

        assertThat(result).isPresent();
        assertThat(result.get().region()).isEqualTo("north");
        assertThat(result.get().signalType()).isEqualTo("AQI");
        assertThat(result.get().timestamp()).isEqualTo(START.plus(Duration.ofHours(2))); // latest reading
        assertThat(result.get().deviationSize()).isEqualTo(8.0); // (130 - 50) / 10
    }

    @Test
    void onlyLastNReadingsCount() {
        // older normal reading is ignored; the last 3 all breach
        assertThat(detect(List.of(at(0, 55), at(1, 120), at(2, 125), at(3, 130)))).isPresent();
    }

    @Test
    void tooFewReadings_noEvent() {
        assertThat(detect(List.of(at(0, 120), at(1, 125)))).isEmpty();
    }

    @Test
    void timeGapInReadings_noEvent() {
        // 120 and 125 are an hour apart, but 125 -> 130 jumps 5 hours
        assertThat(detect(List.of(at(0, 120), at(1, 125), at(6, 130)))).isEmpty();
    }

    @Test
    void nullReading_noEvent() {
        assertThat(detect(Arrays.asList(at(0, 120), null, at(2, 130)))).isEmpty();
    }

    @Test
    void nanReading_noEvent() {
        assertThat(detect(List.of(at(0, 120), at(1, Double.NaN), at(2, 130)))).isEmpty();
    }

    @Test
    void missingBaseline_noEvent() {
        var readings = List.of(at(0, 120), at(1, 125), at(2, 130));
        assertThat(DeviationDetector.detect(Optional.empty(), readings, Z, N, MAX_GAP)).isEmpty();
    }

    @Test
    void flatBaseline_noEvent() {
        var flat = Optional.of(new BaselineSnapshot("north", "AQI", 50.0, 0.0));
        var readings = List.of(at(0, 120), at(1, 125), at(2, 130));
        assertThat(DeviationDetector.detect(flat, readings, Z, N, MAX_GAP)).isEmpty();
    }

    @Test
    void consecutiveRequiredBelowOne_throws() {
        var readings = List.of(at(0, 120));
        assertThatThrownBy(() -> DeviationDetector.detect(baseline, readings, Z, 0, MAX_GAP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
