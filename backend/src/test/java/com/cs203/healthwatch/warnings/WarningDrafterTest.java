package com.cs203.healthwatch.warnings;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WarningDrafterTest {

    private final WarningDrafter drafter = new WarningDrafter();

    private static DetectedEvent pm25Event(double deviation) {
        DetectedEvent event = TestEvents.event(deviation, EventStatus.CONFIRMED, "2026-09-24T03:15:00Z");
        event.setSignalType("pm25");
        event.setRegion("central");
        return event;
    }

    @ParameterizedTest
    @CsvSource({
            "3.1, LOW", "4.99, LOW",
            "5.0, MEDIUM", "7.99, MEDIUM",
            "8.0, HIGH", "11.99, HIGH",
            "12.0, CRITICAL", "40, CRITICAL"})
    void severityFollowsDeviationBands(double deviation, WarningSeverity expected) {
        assertThat(WarningDrafter.severityFor(deviation)).isEqualTo(expected);
    }

    @Test
    void missingDeviationIsLow() {
        assertThat(WarningDrafter.severityFor(null)).isEqualTo(WarningSeverity.LOW);
    }

    @Test
    void draftSaysWhatHappenedTheConfidenceAndOneAction() {
        WarningDrafter.Draft draft = drafter.draft(pm25Event(9.4), ConfidenceLevel.HIGH);

        assertThat(draft.audience()).isEqualTo("GENERAL_PUBLIC");
        assertThat(draft.severity()).isEqualTo(WarningSeverity.HIGH);
        assertThat(draft.text())
                .startsWith("PM2.5 (fine particle pollution) alert for the central region (high severity).")
                .contains("What happened: PM2.5 (fine particle pollution) readings rose well above normal levels")
                .contains("detected at 24 Sep 2026, 11:15 AM (Singapore time)")
                .contains("Confidence: high.")
                .contains("What you should do: " + WarningDrafter.recommendedAction(WarningSeverity.HIGH));
    }

    @Test
    void draftWithoutAConfidenceScoreSaysSo() {
        assertThat(drafter.draft(pm25Event(4.0), null).text()).contains("Confidence: not yet assessed.");
    }

    @Test
    void sameEventAlwaysGivesTheSameDraft() {
        assertThat(drafter.draft(pm25Event(6.0), null)).isEqualTo(drafter.draft(pm25Event(6.0), null));
    }

    @Test
    void everySeverityHasAnAction() {
        for (WarningSeverity severity : WarningSeverity.values()) {
            assertThat(WarningDrafter.recommendedAction(severity)).isNotBlank();
        }
    }
}
