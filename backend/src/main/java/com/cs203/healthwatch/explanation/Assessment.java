package com.cs203.healthwatch.explanation;

/** The AI's verdict on a detected event. */
public enum Assessment {
    /** Sustained, consistent rise that looks like a real change in air quality. */
    GENUINE_TREND,
    /** More likely a sensor glitch, a single odd reading or a data artefact. */
    LIKELY_NOISE,
    /** The data given cannot tell the two apart. */
    UNCERTAIN
}
