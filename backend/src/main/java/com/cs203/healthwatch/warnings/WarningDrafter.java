package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.events.DetectedEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Turns an investigated event into a general-public warning draft (CG-21). The text has three parts: what happened,
 * how confident we are, and one concrete action.
 *
 * <p>Deterministic on purpose: the same event always gives the same draft, and the health advice comes from a fixed
 * table per severity rather than being invented per event. Per-audience drafts (vulnerable groups, healthcare
 * workers) are Sprint 3.
 */
@Component
public class WarningDrafter {

    public static final String GENERAL_PUBLIC = "GENERAL_PUBLIC";

    /** Upper bounds (exclusive) on the robust z-score for each severity. Detection only fires above z = 3. */
    static final double MEDIUM_FROM = 5.0;
    static final double HIGH_FROM = 8.0;
    static final double CRITICAL_FROM = 12.0;

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(SINGAPORE);

    public record Draft(String audience, WarningSeverity severity, String text) {
    }

    /**
     * @param confidenceLevel the event's confidence band (CG-16), or null for events scored before confidence existed
     */
    public Draft draft(DetectedEvent event, ConfidenceLevel confidenceLevel) {
        WarningSeverity severity = severityFor(event.getDeviation());
        String text = String.format(
                "%s alert for the %s region (%s severity).%n"
                        + "What happened: %s readings rose well above normal levels for this area, detected at %s "
                        + "(Singapore time).%n"
                        + "Confidence: %s.%n"
                        + "What you should do: %s",
                signalName(event.getSignalType()),
                event.getRegion(),
                severity.name().toLowerCase(Locale.ROOT),
                signalName(event.getSignalType()),
                WHEN.format(event.getDetectedAt()),
                confidenceLevel == null ? "not yet assessed" : confidenceLevel.name().toLowerCase(Locale.ROOT),
                recommendedAction(severity));
        return new Draft(GENERAL_PUBLIC, severity, text);
    }

    static WarningSeverity severityFor(Double deviation) {
        double z = deviation == null ? 0 : Math.abs(deviation);
        if (z >= CRITICAL_FROM) {
            return WarningSeverity.CRITICAL;
        }
        if (z >= HIGH_FROM) {
            return WarningSeverity.HIGH;
        }
        if (z >= MEDIUM_FROM) {
            return WarningSeverity.MEDIUM;
        }
        return WarningSeverity.LOW;
    }

    static String recommendedAction(WarningSeverity severity) {
        return switch (severity) {
            case LOW -> "Check air quality updates before planning long outdoor activities today.";
            case MEDIUM -> "Cut down on prolonged or strenuous outdoor activity, especially if you have heart or "
                    + "lung conditions.";
            case HIGH -> "Avoid strenuous outdoor activity and keep windows closed while levels stay high.";
            case CRITICAL -> "Stay indoors where possible, keep windows closed, and wear an N95 mask if you must "
                    + "go outside.";
        };
    }

    private static String signalName(String signalType) {
        return "pm25".equalsIgnoreCase(signalType) ? "PM2.5 (fine particle pollution)" : signalType;
    }
}
