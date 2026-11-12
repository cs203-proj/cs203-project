package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.profile.Audience;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Turns an investigated event into a warning draft for one audience: the general public (CG-21), vulnerable groups or
 * healthcare workers (CG-154). The text has three parts: what happened, how confident we are, and one concrete action.
 *
 * <p>Deterministic on purpose: the same event and audience always give the same draft, and the health advice comes
 * from a fixed table per audience and severity (docs/warning-advice.md) rather than being invented per event. The AI
 * never writes advice; its only text is the optional, separately labelled "why this matters" line
 * ({@code ai_line}, see {@link AiLineService}).
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
        return draft(event, confidenceLevel, Audience.GENERAL_PUBLIC);
    }

    /**
     * @param confidenceLevel the event's confidence band (CG-16), or null for events scored before confidence existed
     * @param audience        who the draft is for; the general-public text is the CG-21 text, unchanged
     */
    public Draft draft(DetectedEvent event, ConfidenceLevel confidenceLevel, Audience audience) {
        WarningSeverity severity = severityFor(event.getDeviation());
        String text = String.format(
                "%s alert for the %s region (%s severity)%s.%n"
                        + "What happened: %s readings rose well above normal levels for this area, detected at %s "
                        + "(Singapore time).%n"
                        + "Confidence: %s.%n"
                        + "What you should do: %s",
                signalName(event.getSignalType()),
                event.getRegion(),
                severity.name().toLowerCase(Locale.ROOT),
                audienceSuffix(audience),
                signalName(event.getSignalType()),
                WHEN.format(event.getDetectedAt()),
                confidenceLevel == null ? "not yet assessed" : confidenceLevel.name().toLowerCase(Locale.ROOT),
                recommendedAction(audience, severity));
        return new Draft(audience.code(), severity, text);
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

    static String recommendedAction(Audience audience, WarningSeverity severity) {
        return switch (audience) {
            case GENERAL_PUBLIC -> recommendedAction(severity);
            case VULNERABLE -> vulnerableAction(severity);
            case HEALTHCARE_WORKER -> healthcareWorkerAction(severity);
        };
    }

    /** General-public advice (CG-21). */
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

    /**
     * Elderly people, pregnant women, children and people with chronic lung or heart disease. Follows the MOH health
     * advisory table published by NEA; see docs/warning-advice.md for the source and the severity mapping.
     */
    private static String vulnerableAction(WarningSeverity severity) {
        return switch (severity) {
            case LOW -> "Normal activities are fine, but check air quality updates and see a doctor if you develop "
                    + "breathing difficulties.";
            case MEDIUM -> "Minimise prolonged or strenuous outdoor activity, and avoid it if you have chronic lung or "
                    + "heart disease. See a doctor if you develop breathing difficulties.";
            case HIGH -> "Minimise outdoor activity, and avoid it if you have chronic lung or heart disease. Seek "
                    + "medical advice if you feel unwell.";
            case CRITICAL -> "Avoid outdoor activity. Seek medical advice if you feel unwell, and ask your doctor "
                    + "before using an N95 mask.";
        };
    }

    /** Derived from the same advisory: what to expect from, and tell, at-risk patients. See docs/warning-advice.md. */
    private static String healthcareWorkerAction(WarningSeverity severity) {
        return switch (severity) {
            case LOW -> "Watch for patients with chronic lung or heart disease reporting breathing difficulties.";
            case MEDIUM -> "Expect more respiratory and cardiac presentations, and advise elderly, pregnant and young "
                    + "patients and those with chronic lung or heart disease to minimise prolonged or strenuous "
                    + "outdoor activity.";
            case HIGH -> "Expect more respiratory and cardiac presentations, and advise at-risk patients to minimise "
                    + "outdoor activity, or avoid it if they have chronic lung or heart disease.";
            case CRITICAL -> "Prepare for a surge in respiratory and cardiac presentations, advise at-risk patients to "
                    + "avoid outdoor activity, and follow your facility's haze response plan.";
        };
    }

    /** Empty for the general public, so the CG-21 text stays exactly as it was. */
    private static String audienceSuffix(Audience audience) {
        return switch (audience) {
            case GENERAL_PUBLIC -> "";
            case VULNERABLE -> " - advice for vulnerable groups";
            case HEALTHCARE_WORKER -> " - advice for healthcare workers";
        };
    }

    private static String signalName(String signalType) {
        return "pm25".equalsIgnoreCase(signalType) ? "PM2.5 (fine particle pollution)" : signalType;
    }
}
