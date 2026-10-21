package com.cs203.healthwatch.warnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs203.healthwatch.common.exception.BadRequestException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WarningStatusTest {

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "pending_review", "Pending Review", "pending-review", "  pending_review  "})
    void acceptsCaseSpacingAndHyphenVariants(String input) {
        assertThat(WarningStatus.parse(input)).isEqualTo(WarningStatus.PENDING_REVIEW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullOrBlankIsA400_notA500(String input) {
        assertThatThrownBy(() -> WarningStatus.parse(input))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Status is required");
    }

    @Test
    void unknownValueListsTheValidOnes() {
        assertThatThrownBy(() -> WarningStatus.parse("deleted"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown status 'deleted'. Valid values: PENDING_REVIEW, APPROVED, SUPPRESSED");
    }

    @Test
    void parsingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr")); // Turkish: "i".toUpperCase() is "İ", not "I"
            assertThat(WarningStatus.parse("suppressed")).isEqualTo(WarningStatus.SUPPRESSED);
            assertThat(WarningStatus.parse("pending_review")).isEqualTo(WarningStatus.PENDING_REVIEW);
        } finally {
            Locale.setDefault(original);
        }
    }
}