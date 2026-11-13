package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.detection.ReadingSnapshot;
import com.cs203.healthwatch.detection.ScopeResult;
import com.cs203.healthwatch.detection.SpatialScope;
import java.util.Locale;

/** Builds the instructions and the facts sent to the model. Pure functions, so they are unit-tested directly. */
public final class ExplanationPrompt {

    private ExplanationPrompt() {
    }

    public static final String SYSTEM_INSTRUCTION = """
            You help an admin of a public-health early-warning system decide whether a flagged air-quality event \
            needs attention. The system flagged the event because the last few readings were all far above a \
            robust statistical baseline (median and MAD scaled by 1.4826), measured as a robust z-score.

            Rules:
            - Use only the facts provided. Never invent readings, news, weather or incidents.
            - Name possible causes as hypotheses, not facts (e.g. "consistent with regional haze", not "caused by \
            haze").
            - Decide the assessment:
              GENUINE_TREND if readings rise and stay high in a consistent way;
              LIKELY_NOISE if it looks like a sensor glitch, a single jump, an implausible value or a data artefact;
              UNCERTAIN if the data cannot tell these apart (for example too few readings, or stale data).
            - If the data quality note says data is stale or partial, say so in the explanation and do not choose \
            GENUINE_TREND on that data alone.
            - The admin is not a statistician: explain what the z-score means in plain words.
            - A "Cross-region comparison" section gives the scope, computed by the system. Use that scope exactly as \
            given; never upgrade, downgrade or recompute it. Explain what it means: LOCAL = only the flagged region is \
            above the threshold; MULTI_REGION = several regions are above but not all; ISLAND_WIDE = every region is \
            above; UNKNOWN = the comparison could not be made.
            - Name no region and quote no number that is not in the facts. State coverage honestly, for example \
            "3 of 5 regions reporting". If regions are missing, say the picture is incomplete.
            - If the scope is UNKNOWN, say plainly that the comparison could not be made and give the reason shown. \
            Never guess whether the spike is local or island-wide.
            - Never describe regions as nearby, neighbouring, adjacent or close to each other, and make no other \
            claim about their geography.
            - likelyCause stays a hedged hypothesis (for example "consistent with ..."), never stated as fact.
            - scopeSummary: 1 to 2 sentences about the scope only, following the rules above.
            - explanation: at most 4 sentences. evidence: 2 to 5 short facts taken from the data. assumptions: 1 to \
            4 things your explanation takes for granted.
            """;

    /** The facts about one event, as plain text. */
    public static String facts(ExplanationContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Flagged event").append(c.replay() ? " (REPLAY of historical data for a demo, not live)" : "")
                .append('\n');
        sb.append("- Signal: ").append(c.signalType()).append('\n');
        sb.append("- PM2.5 readings are in \u00b5g/m\u00b3.").append('\n');
        sb.append("- Region: ").append(c.region()).append('\n');
        sb.append("- Detected at (UTC): ").append(c.detectedAt()).append('\n');
        sb.append("- Largest robust z-score among breaching readings: ").append(fmt(c.deviation())).append('\n');
        sb.append("- Detection rule: ").append(c.consecutiveReadings())
                .append(" consecutive readings each with robust z-score above ").append(fmt(c.zThreshold()))
                .append('\n');
        sb.append("- Baseline median: ").append(fmt(c.baselineMedian()))
                .append("; baseline spread (MAD x 1.4826): ").append(fmt(c.baselineScaledMad())).append('\n');
        sb.append("- Data quality: ").append(c.dataQuality().note()).append('\n');
        sb.append('\n').append("Recent valid readings, oldest first (time UTC, value, robust z-score):").append('\n');
        if (c.readings().isEmpty()) {
            sb.append("(none)").append('\n');
        }
        for (ReadingSnapshot r : c.readings()) {
            double z = c.baselineScaledMad() > 0 ? (r.value() - c.baselineMedian()) / c.baselineScaledMad() : 0;
            sb.append(r.observedAt()).append(", ").append(fmt(r.value())).append(", z=").append(fmt(z)).append('\n');
        }
        appendScope(sb, c);
        return sb.toString();
    }

    private static void appendScope(StringBuilder sb, ExplanationContext c) {
        ScopeResult s = c.scope();
        sb.append('\n').append("Cross-region comparison (computed by the system, not by you):").append('\n');
        sb.append("- Scope: ").append(s.scope()).append('\n');
        sb.append("- Known regions: ").append(String.join(", ", c.knownRegions())).append(" (")
                .append(c.knownRegions().size()).append(" in total)").append('\n');
        if (s.scope() == SpatialScope.UNKNOWN) {
            sb.append("- Why unknown: ").append(s.reason() == null ? "not available" : s.reason()).append('\n');
            return;
        }
        sb.append("- Regions reporting: ").append(s.regionsCompared().size()).append(" of ")
                .append(c.knownRegions().size()).append(" (").append(String.join(", ", s.regionsCompared()))
                .append(')').append('\n');
        sb.append("- Regions above the threshold: ").append(String.join(", ", s.regionsAbove())).append('\n');
        sb.append("- Robust z-score of each reporting region at this time:").append('\n');
        s.regionZScores().forEach((region, z) -> sb.append("  ").append(region).append(": z=").append(fmt(z))
                .append('\n'));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
