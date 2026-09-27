package com.cs203.healthwatch.service;

import com.cs203.healthwatch.model.EventStatus;
import java.util.List;
import org.springframework.stereotype.Component;

// TEMPORARY: always returns no events. Delete this class once a real EventReader is backed by the events table.
@Component
public class StubEventReader implements EventReader {

    @Override
    public Result find(EventStatus status, long offset, int limit) {
        return new Result(List.of(), 0);
    }
}
