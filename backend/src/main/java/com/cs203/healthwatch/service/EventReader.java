package com.cs203.healthwatch.service;

import com.cs203.healthwatch.dto.EventResponse;
import com.cs203.healthwatch.model.EventStatus;
import java.util.List;

/**
 * Where flagged events are read from. Implemented on top of the events table once it and its entity exist
 * (CG-2 / CG-13); until then {@link StubEventReader} stands in.
 */
public interface EventReader {

    /**
     * @param status filter, or null for all statuses
     * @return the requested page of matching events sorted by deviation size, largest first (ties broken
     *         deterministically so pages never overlap), plus the total number of matches across all pages
     */
    Result find(EventStatus status, long offset, int limit);

    record Result(List<EventResponse> items, long total) {
    }
}
