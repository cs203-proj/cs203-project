package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.profile.Audience;
import java.util.Locale;

/** Builds the instructions and the facts sent to the model for the "why this matters" line. Pure functions. */
public final class AiLinePrompt {

    private AiLinePrompt() {
    }

    public static final String SYSTEM_INSTRUCTION = """
            You write a short "why this matters" line for an air-quality health warning in Singapore. The warning \
            already contains official advice written by public-health staff. Your only job is to explain why this \
            event matters for the audience named below. An admin reviews the line before anyone sees it, and it is \
            shown labelled "AI-generated".

            Rules:
            - whyItMatters: one or two sentences, at most 50 words in total, plain English, no lists.
            - Explain only why it matters. Never give advice, actions, instructions or recommendations (no "should", \
            "stay", "avoid", "wear", "see a doctor"). Never mention masks, medicines or treatments.
            - Use only the facts provided. Never invent readings, numbers, places, news, weather or incidents.
            - The likely cause is a hypothesis: say "may be" or "consistent with", never state it as fact. If the \
            assessment is UNCERTAIN or LIKELY_NOISE, say the signal may not be real.
            - The same line is shown to everyone in the audience, so never say or imply you know a reader's own \
            conditions (write "people with asthma", not "because you have asthma").
            - Vulnerable groups: elderly people, pregnant women, children and people with chronic lung or heart \
            disease. Healthcare workers: why it matters for their patients and their work.
            """;

    /** The facts about one warning, as plain text. */
    public static String facts(AiLineContext c) {
        return "Warning\n"
                + "- Audience: " + audienceLabel(c.audience()) + '\n'
                + "- Region of Singapore: " + c.region() + '\n'
                + "- Pollutant: " + signalName(c.signalType()) + '\n'
                + "- Severity: " + c.severity().name().toLowerCase(Locale.ROOT) + '\n'
                + "- Confidence: " + (c.confidenceLevel() == null
                        ? "not yet assessed" : c.confidenceLevel().name().toLowerCase(Locale.ROOT)) + '\n'
                + "- Analyst assessment of the event: " + c.assessment().name() + '\n'
                + "- Likely cause (hypothesis): " + c.likelyCause() + '\n';
    }

    static String audienceLabel(Audience audience) {
        return switch (audience) {
            case GENERAL_PUBLIC -> "the general public";
            case VULNERABLE -> "vulnerable groups";
            case HEALTHCARE_WORKER -> "healthcare workers";
        };
    }

    private static String signalName(String signalType) {
        return "pm25".equalsIgnoreCase(signalType) ? "PM2.5 (fine particle pollution)" : signalType;
    }
}
