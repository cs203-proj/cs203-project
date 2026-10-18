package com.cs203.healthwatch.explanation;

import java.util.List;

/** What the AI returns for one event, after validation. */
public record AiExplanation(
        Assessment assessment,
        String likelyCause,
        String explanation,
        List<String> evidence,
        List<String> assumptions
) {}
