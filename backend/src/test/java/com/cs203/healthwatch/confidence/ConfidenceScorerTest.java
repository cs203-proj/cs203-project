package com.cs203.healthwatch.confidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfidenceScorerTest {

    private static ConfidenceResult score(long staleSeconds, int count, double deviation, Double agreement) {
        return ConfidenceScorer.score(new ConfidenceInputs(staleSeconds, count, deviation, agreement));
    }

    @Test
    void sameInputs_alwaysGiveTheSameScore() {
        var a = score(1800, 20, 4.2, null);
        var b = score(1800, 20, 4.2, null);
        assertThat(a).isEqualTo(b);
        assertThat(a.formulaVersion()).isEqualTo(ConfidenceScorer.FORMULA_VERSION);
    }

    @Test
    void worked_example_matchesDocs() {
        // deviation 0.42*0.35 + count 20/24*0.25 + fresh 0.5*0.25 = 0.147 + 0.20833 + 0.125 = 0.48033 ; / 0.85
        var r = score(5400, 20, 4.2, null);
        assertThat(r.score()).isEqualTo(0.5651);
        assertThat(r.level()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void strongFreshFullWindow_isHigh() {
        var r = score(0, 24, 10.0, null);
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.level()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void weakStaleSparse_isLow() {
        var r = score(3 * 3600, 3, 3.1, null);
        assertThat(r.level()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(r.score()).isLessThan(ConfidenceScorer.MEDIUM_FROM);
    }

    @Test
    void stalerData_scoresLower() {
        assertThat(score(7200, 24, 6, null).score()).isLessThan(score(600, 24, 6, null).score());
    }

    @Test
    void moreReadings_scoreHigher() {
        assertThat(score(600, 24, 6, null).score()).isGreaterThan(score(600, 6, 6, null).score());
    }

    @Test
    void biggerDeviation_scoresHigher() {
        assertThat(score(600, 24, 9, null).score()).isGreaterThan(score(600, 24, 3.5, null).score());
    }

    @Test
    void agreementRaisesAndDisagreementLowersTheScore() {
        double without = score(600, 24, 6, null).score();
        assertThat(score(600, 24, 6, 1.0).score()).isGreaterThan(without);
        assertThat(score(600, 24, 6, 0.0).score()).isLessThan(without);
    }

    @Test
    void missingAgreement_isLeftOut_notTreatedAsDisagreement() {
        // with every other input maxed, a missing agreement must still be able to reach 1.0
        assertThat(score(0, 24, 10, null).score()).isEqualTo(1.0);
    }

    @Test
    void outOfRangeAndNonFiniteInputs_neverProduceNaNOrOutOfRangeScores() {
        for (double dev : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5, 1e300}) {
            var r = score(-100, -3, dev, 7.0);
            assertThat(r.score()).isBetween(0.0, 1.0);
            assertThat(Double.isNaN(r.score())).isFalse();
        }
    }

    @Test
    void bandBoundaries() {
        assertThat(ConfidenceScorer.levelFor(0.3999)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceScorer.levelFor(0.40)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceScorer.levelFor(0.6999)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceScorer.levelFor(0.70)).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void weightsSumToOne() {
        assertThat(ConfidenceScorer.WEIGHT_DEVIATION + ConfidenceScorer.WEIGHT_READING_COUNT
                + ConfidenceScorer.WEIGHT_STALENESS + ConfidenceScorer.WEIGHT_AGREEMENT)
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
    }
}
