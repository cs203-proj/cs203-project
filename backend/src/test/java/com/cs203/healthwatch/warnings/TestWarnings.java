package com.cs203.healthwatch.warnings;

import java.util.UUID;

/** Builds unsaved warnings for tests. */
public final class TestWarnings {

    private TestWarnings() {
    }

    public static Warning warning(UUID eventId, WarningSeverity severity, WarningStatus status) {
        Warning warning = new Warning();
        warning.setEventId(eventId);
        warning.setSeverity(severity);
        warning.setStatus(status);
        warning.setWarningText("PM2.5 is elevated in the central region. Limit outdoor activity.");
        return warning;
    }
}
