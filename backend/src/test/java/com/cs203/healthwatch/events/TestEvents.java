package com.cs203.healthwatch.events;

import java.time.Instant;

/** Builds unsaved live events for tests. */
public final class TestEvents {

    private TestEvents() {
    }

    public static DetectedEvent event(double deviation, EventStatus status, String detectedAt) {
        DetectedEvent event = new DetectedEvent();
        event.setSignalType("AQI");
        event.setRegion("Singapore");
        event.setDetectedAt(Instant.parse(detectedAt));
        event.setDeviation(deviation);
        event.setStatus(status);
        return event;
    }
}
