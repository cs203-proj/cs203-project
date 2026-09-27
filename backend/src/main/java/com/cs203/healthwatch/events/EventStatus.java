package com.cs203.healthwatch.events;

import com.cs203.healthwatch.common.exception.BadRequestException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle of a flagged event. Allowed transitions (see README):
 * <pre>
 * NEW          -> UNDER_REVIEW, DISMISSED
 * UNDER_REVIEW -> CONFIRMED, DISMISSED
 * DISMISSED    -> UNDER_REVIEW   (reopen)
 * CONFIRMED    -> (final)
 * </pre>
 */
public enum EventStatus {
    NEW, UNDER_REVIEW, CONFIRMED, DISMISSED;

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

    /** Statuses this one may move to. Moving to the same status is never allowed. */
    public Set<EventStatus> allowedNext() {
        return switch (this) {
            case NEW -> EnumSet.of(UNDER_REVIEW, DISMISSED);
            case UNDER_REVIEW -> EnumSet.of(CONFIRMED, DISMISSED);
            case DISMISSED -> EnumSet.of(UNDER_REVIEW);
            case CONFIRMED -> EnumSet.noneOf(EventStatus.class);
        };
    }

    public boolean canMoveTo(EventStatus target) {
        return allowedNext().contains(target);
    }
}
