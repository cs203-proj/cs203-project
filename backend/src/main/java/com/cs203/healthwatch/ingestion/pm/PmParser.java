package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.ingestion.readings.Reading;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PmParser {

    public List<Reading> parseAll(JsonNode root, String region, UUID sourceId) {
        List<Reading> out = new ArrayList<>();

        int code = root.path("code").asInt(-1);
        if (code != 0) {
            Reading r = new Reading();
            r.setSourceId(sourceId);
            r.setRegion(region);
            r.setRawPayload(root.toString());
            r.setMalformed(true);

            out.add(r);
            return out;
        }

        JsonNode items = root.path("data").path("items");
        for (JsonNode item : items) {
            out.add(parse(item, region, sourceId));
        }
        return out;
    }

    public Reading parse(JsonNode item, String region, UUID sourceId) {
        Reading r = new Reading();
        r.setSourceId(sourceId);
        r.setRegion(region);
        r.setRawPayload(item.toString());
        try {
            JsonNode ts = item.path("timestamp");
            if (ts.isMissingNode() || ts.isNull()) { 
                r.setMalformed(true);
                return r; 
            }

            r.setObservedAt(OffsetDateTime.parse(ts.asText()));

            JsonNode v = item.path("readings").path("pm25_one_hourly").path(region);
            if (!v.isNumber() || v.asDouble() < 0 || v.asDouble() > 1000) { 
                r.setMalformed(true); 
                return r; 
            }

            r.setValue(v.asDouble());
            r.setMalformed(false);

        } catch (DateTimeParseException e) {
            r.setMalformed(true);
        }

        return r;
    }
}