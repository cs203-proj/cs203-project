package com.cs203.healthwatch.events;

import static com.cs203.healthwatch.events.EventStatus.CONFIRMED;
import static com.cs203.healthwatch.events.EventStatus.DISMISSED;
import static com.cs203.healthwatch.events.EventStatus.NEW;
import static com.cs203.healthwatch.events.EventStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs203.healthwatch.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventStatusTest {

    // The full transition table from the README. Every pair not marked true must be rejected.
    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @CsvSource({
            "NEW, NEW, false",
            "NEW, UNDER_REVIEW, true",
            "NEW, CONFIRMED, false",
            "NEW, DISMISSED, true",
            "UNDER_REVIEW, NEW, false",
            "UNDER_REVIEW, UNDER_REVIEW, false",
            "UNDER_REVIEW, CONFIRMED, true",
            "UNDER_REVIEW, DISMISSED, true",
            "CONFIRMED, NEW, false",
            "CONFIRMED, UNDER_REVIEW, false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, DISMISSED, false",
            "DISMISSED, NEW, false",
            "DISMISSED, UNDER_REVIEW, true",
            "DISMISSED, CONFIRMED, false",
            "DISMISSED, DISMISSED, false",
    })
    void transitionTable(EventStatus from, EventStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNDER_REVIEW", "under_review", "Under Review", "under-review", "  UNDER_REVIEW "})
    void parseAcceptsCommonSpellings(String value) {
        assertThat(EventStatus.parse(value)).isEqualTo(UNDER_REVIEW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "", "open", "UNDERREVIEW"})
    void parseRejectsUnknownValuesListingTheValidOnes(String value) {
        assertThatThrownBy(() -> EventStatus.parse(value))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Valid values: NEW, UNDER_REVIEW, CONFIRMED, DISMISSED");
    }

    @Test
    void confirmedIsFinal() {
        assertThat(CONFIRMED.allowedNext()).isEmpty();
        assertThat(DISMISSED.allowedNext()).containsExactly(UNDER_REVIEW);
        assertThat(NEW.allowedNext()).containsExactlyInAnyOrder(UNDER_REVIEW, DISMISSED);
    }
}
