package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.profile.Audience;

/**
 * The facts the AI gets to write the "why this matters" line for one warning (CG-154): the audience plus the event's
 * CG-135 explanation. Nothing about any individual user is included, since one warning row is shared by everyone in
 * the audience. The recommended action is deliberately not sent: the AI never writes or changes advice.
 *
 * @param confidenceLevel null if the event was never scored (CG-16)
 */
public record AiLineContext(
        Audience audience,
        String region,
        String signalType,
        WarningSeverity severity,
        ConfidenceLevel confidenceLevel,
        Assessment assessment,
        String likelyCause
) {
}
