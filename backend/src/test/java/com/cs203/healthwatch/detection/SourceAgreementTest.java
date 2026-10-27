package com.cs203.healthwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceAgreementTest {

    private static final Instant AT = Instant.parse("2026-10-10T04:00:00Z");
    private static final double Z = 3.0;

    // median 50, scaledMad 10: a reading breaches when it is above 80
    private static BaselineSnapshot base(String region) {
        return new BaselineSnapshot(region, "pm25", 50.0, 10.0);
    }

    private static final Map<String, BaselineSnapshot> BASELINES = Map.of(
            "north", base("north"), "south", base("south"), "east", base("east"), "west", base("west"));

    private static ReadingSnapshot reading(String region, double value) {
        return new ReadingSnapshot(region, "pm25", value, AT);
    }

    @Test
    void allRegionsBreach_isOne() {
        assertThat(SourceAgreement.compute(List.of(reading("north", 120), reading("south", 130),
                reading("east", 100), reading("west", 95)), BASELINES, Z)).isEqualTo(1.0);
    }

    @Test
    void noRegionBreaches_isZero() {
        assertThat(SourceAgreement.compute(List.of(reading("north", 55), reading("south", 60),
                reading("east", 40), reading("west", 70)), BASELINES, Z)).isEqualTo(0.0);
    }

    @Test
    void mixedRegions_isTheShareThatBreach() {
        assertThat(SourceAgreement.compute(List.of(reading("north", 120), reading("south", 60),
                reading("east", 100), reading("west", 55)), BASELINES, Z)).isEqualTo(0.5);
    }

    @Test
    void exactlyAtThreshold_doesNotCountAsAgreeing() {
        assertThat(SourceAgreement.compute(List.of(reading("north", 80)), BASELINES, Z)).isEqualTo(0.0); // z = 3.0
    }

    @Test
    void noReadings_isNull() {
        assertThat(SourceAgreement.compute(List.of(), BASELINES, Z)).isNull();
    }

    @Test
    void regionWithoutBaseline_isLeftOut_notCountedAsDisagreeing() {
        var onlyNorth = Map.of("north", base("north"));
        assertThat(SourceAgreement.compute(List.of(reading("north", 130), reading("south", 55)), onlyNorth, Z))
                .isEqualTo(1.0);
        assertThat(SourceAgreement.compute(List.of(reading("south", 130)), onlyNorth, Z)).isNull();
    }

    @Test
    void regionWithZeroMad_isLeftOut() {
        var flat = Map.of("north", new BaselineSnapshot("north", "pm25", 50.0, 0.0), "south", base("south"));
        assertThat(SourceAgreement.compute(List.of(reading("north", 130), reading("south", 55)), flat, Z))
                .isEqualTo(0.0);
        assertThat(SourceAgreement.compute(List.of(reading("north", 130)), flat, Z)).isNull();
    }

    @Test
    void nullOrNonFiniteReadings_areLeftOut() {
        assertThat(SourceAgreement.compute(
                Arrays.asList(null, reading("north", Double.NaN), reading("south", Double.POSITIVE_INFINITY)),
                BASELINES, Z)).isNull();
    }

    @Test
    void resultIsAlwaysBetweenZeroAndOne() {
        Double result = SourceAgreement.compute(
                List.of(reading("north", 120), reading("south", 60), reading("east", 99)), BASELINES, Z);
        assertThat(result).isBetween(0.0, 1.0);
    }
}
