package com.cs203.healthwatch.detection;

import com.cs203.healthwatch.events.DetectedEvent;

/** What happened when the demo window was replayed through detection. event and baseline may be null. */
public record ReplayResult(
        Outcome outcome,
        String message,
        int readingsScanned,
        BaselineSnapshot baseline,
        DetectedEvent event
) {
    public enum Outcome { CREATED, ALREADY_OPEN, NO_ANOMALY, NO_READINGS, NO_BASELINE }
}
