package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.common.exception.BadRequestException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How serious a warning is, declared from least to most severe. The review queue (CG-29) does not use this
 * declaration order: it ranks with the CASE in {@link WarningRepository#QUEUE_ORDER}, so a new value must also get
 * its own WHEN there (and in the chk_warnings_severity constraint, via a new migration).
 */
public enum WarningSeverity {
    LOW, MEDIUM, HIGH, CRITICAL;

    /** Parses a user-supplied value ("high", "High"); rejects anything else with a 400. Mirrors WarningStatus. */
    public static WarningSeverity parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Severity is required");
        }

        String normalised = value.trim().toUpperCase(Locale.ROOT);
        for (WarningSeverity severity : values()) {
            if (severity.name().equals(normalised)) {
                return severity;
            }
        }
        String valid = Arrays.stream(values()).map(WarningSeverity::name).collect(Collectors.joining(", "));
        throw new BadRequestException("Unknown severity '" + value + "'. Valid values: " + valid);
    }

    /**
     * True when this severity is less serious than {@code other}. Relies on the declaration order above running from
     * least to most severe, which is exactly what a downgrade (CG-28) has to check.
     */
    public boolean isLessSevereThan(WarningSeverity other) {
        return ordinal() < other.ordinal();
    }
}
