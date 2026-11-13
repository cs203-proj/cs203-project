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

    // set region as "ALL" if malformed json obtained as a whole (so all regions are malformed)
    public static final String ALL_REGIONS = "ALL"; 

    // Readings outside this range are physically implausible for PM2.5 (µg/m³) and are flagged malformed.
    static final double MIN_VALUE = 0;
    static final double MAX_VALUE = 1000;

    public List<Reading> parseAll(JsonNode root, String region, UUID sourceId) {
        List<Reading> out = new ArrayList<>();

        if (!isValidResponse(root)) {
            out.add(malformed(root, ALL_REGIONS, sourceId));
            return out;
        }

        JsonNode items = root.path("data").path("items");

        for (JsonNode item : items) {
            out.add(parse(item, region, sourceId));
        }
        return out;
    }

    public Reading parse(JsonNode item, String region, UUID sourceId) {
        OffsetDateTime observedAt;
        try {
            JsonNode ts = item.path("timestamp");
            if (ts.isMissingNode() || ts.isNull()) {
                return malformed(item, region, sourceId);
            }
            observedAt = OffsetDateTime.parse(ts.asText());
        } catch (DateTimeParseException e) {
            return malformed(item, region, sourceId);
        }

        JsonNode v = item.path("readings").path("pm25_one_hourly").path(region);
        if (!v.isNumber() || v.asDouble() < MIN_VALUE || v.asDouble() > MAX_VALUE) {
            Reading bad = malformed(item, region, sourceId);
            bad.setObservedAt(observedAt);
            return bad;
        }

        Reading r = new Reading();
        r.setSourceId(sourceId);
        r.setRegion(region);
        r.setRawPayload(item.toString());
        r.setObservedAt(observedAt);
        r.setValue(v.asDouble());
        r.setMalformed(false);
        return r;
    }

    /** Region names the feed itself reports, for the log message when a configured region is missing. */
    public List<String> regionsInFeed(JsonNode root) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : root.path("data").path("items")) {
            item.path("readings").path("pm25_one_hourly").fieldNames().forEachRemaining(name -> {
                if (!names.contains(name)) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    public boolean isValidResponse(JsonNode root) {
        return root.path("code").asInt(-1) == 0 && root.path("data").path("items").isArray();
    }

    public Reading malformed(JsonNode raw, String region, UUID sourceId) {
        Reading r = new Reading();
        r.setSourceId(sourceId);
        r.setRegion(region);
        r.setRawPayload(raw.toString());
        r.setMalformed(true);
        return r;
    }
}