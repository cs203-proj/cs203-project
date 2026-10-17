package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.common.exception.BadRequestException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Review state of a generated warning. CG-21 creates warnings as PENDING_REVIEW; CG-28 moves them to APPROVED or
 * SUPPRESSED. Nothing is sent to end users in Sprint 2.
 */
public enum WarningStatus {
    PENDING_REVIEW, APPROVED, SUPPRESSED;

    /** Parses a user-supplied value ("pending_review", "pending review"); rejects anything else with a 400. */
    public static WarningStatus parse(String value) {
        String normalised = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (WarningStatus status : values()) {
            if (status.name().equals(normalised)) {
                return status;
            }
        }
        String valid = Arrays.stream(values()).map(WarningStatus::name).collect(Collectors.joining(", "));
        throw new BadRequestException("Unknown status '" + value + "'. Valid values: " + valid);
    }
}
