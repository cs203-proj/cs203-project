package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.EventListResponse;
import com.cs203.healthwatch.model.EventStatus;
import org.springframework.stereotype.Service;

@Service
public class EventService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final EventReader eventReader;

    public EventService(EventReader eventReader) {
        this.eventReader = eventReader;
    }

    /** @param status optional status filter (case-insensitive); null returns every status */
    public EventListResponse list(String status, int limit, long offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be 0 or greater");
        }
        EventStatus statusFilter = (status == null) ? null : EventStatus.parse(status);

        EventReader.Result result = eventReader.find(statusFilter, offset, limit);
        return new EventListResponse(result.items(), result.total(), limit, offset);
    }
}
