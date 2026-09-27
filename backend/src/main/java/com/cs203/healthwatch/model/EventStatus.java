package com.cs203.healthwatch.model;

import com.cs203.healthwatch.common.exception.BadRequestException;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum EventStatus {
    NEW,
    UNDER_REVIEW,
    CONFIRMED,
    DISMISSED;

    /** Parses a user-supplied value ("new", "under_review", "under review"); rejects anything else with a 400. */
    public static EventStatus parse(String value) {
        String normalised = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (EventStatus status : values()) {
            if (status.name().equals(normalised)) {
                return status;
            }
        }
        String valid = Arrays.stream(values()).map(EventStatus::name).collect(Collectors.joining(", "));
        throw new BadRequestException("Unknown status '" + value + "'. Valid values: " + valid);
    }
}
