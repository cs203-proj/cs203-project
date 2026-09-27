package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.ingestion.readings.Reading;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PmParser. No mocks needed — PmParser is a stateless,
 * pure transformation from JsonNode to Reading, so we test it directly
 * against hand-built JSON fixtures shaped like the real datagov.sg payload.
 */
class PmParserTest {

    private static final String REGION = "central";
    private static final UUID SOURCE_ID = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();
    private PmParser parser;

    @BeforeEach
    void setUp() {
        parser = new PmParser();
    }

    private JsonNode json(String jsonText) {
        try {
            return mapper.readTree(jsonText);
        } catch (Exception e) {
            throw new RuntimeException("bad test fixture JSON", e);
        }
    }

    // ---------------------------------------------------------------
    // parse(): single item, valid case
    // ---------------------------------------------------------------

    @Test
    void validItemIsParsedAndNotFlaggedMalformed() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": 42.5 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isFalse();
        assertThat(r.getValue()).isEqualTo(42.5);
        assertThat(r.getRegion()).isEqualTo(REGION);
        assertThat(r.getSourceId()).isEqualTo(SOURCE_ID);
        assertThat(r.getObservedAt()).isEqualTo(OffsetDateTime.parse("2024-01-01T08:00:00+08:00"));
        assertThat(r.getRawPayload()).isEqualTo(item.toString());
    }

    // ---------------------------------------------------------------
    // parse(): missing-field cases
    // ---------------------------------------------------------------

    @Test
    void missingTimestampFieldIsMalformed() {
        JsonNode item = json("""
            {
              "readings": { "pm25_one_hourly": { "central": 42.5 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getObservedAt()).isNull();
        assertThat(r.getValue()).isNull(); // never reached the value check
        assertThat(r.getRawPayload()).isEqualTo(item.toString()); // raw payload preserved regardless
    }

    @Test
    void nullTimestampIsMalformed() {
        JsonNode item = json("""
            {
              "timestamp": null,
              "readings": { "pm25_one_hourly": { "central": 42.5 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getObservedAt()).isNull();
    }

    @Test
    void missingReadingsBlockEntirelyIsMalformed() {
        JsonNode item = json("""
            { "timestamp": "2024-01-01T08:00:00+08:00" }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getValue()).isNull();
    }

    @Test
    void missingRegionKeyIsMalformed() {
        // Payload has readings for other regions but not the one we're ingesting.
        // PmParser validates the value before populating any fields, so a malformed
        // row carries only sourceId, region and rawPayload (observedAt stays null).
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "north": 30.0 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getValue()).isNull();
        assertThat(r.getObservedAt()).isNull();
        assertThat(r.getRawPayload()).isEqualTo(item.toString());
    }

    @Test
    void numericStringValueIsMalformed() {
        // "42.5" as a string is rejected: only JSON numbers are accepted
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": "42.5" } }
            }
            """);

        assertThat(parser.parse(item, REGION, SOURCE_ID).isMalformed()).isTrue();
    }

    // ---------------------------------------------------------------
    // parse(): bad-value cases
    // ---------------------------------------------------------------

    @Test
    void unparsableTimestampStringIsMalformed() {
        JsonNode item = json("""
            {
              "timestamp": "not-a-date",
              "readings": { "pm25_one_hourly": { "central": 42.5 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getObservedAt()).isNull(); // exception thrown before assignment completes
    }

    @Test
    void nonNumericReadingValueIsMalformed() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": "N/A" } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getValue()).isNull();
    }

    @Test
    void negativeReadingValueIsMalformed() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": -0.1 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
    }

    @Test
    void readingValueAboveUpperBoundIsMalformed() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": 1000.1 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isTrue();
    }

    // ---------------------------------------------------------------
    // parse(): boundary values (inclusive range checks — easy off-by-one spot)
    // ---------------------------------------------------------------

    @Test
    void boundaryValueZeroIsValid() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": 0 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isFalse();
        assertThat(r.getValue()).isEqualTo(0.0);
    }

    @Test
    void boundaryValueOneThousandIsValid() {
        JsonNode item = json("""
            {
              "timestamp": "2024-01-01T08:00:00+08:00",
              "readings": { "pm25_one_hourly": { "central": 1000 } }
            }
            """);

        Reading r = parser.parse(item, REGION, SOURCE_ID);

        assertThat(r.isMalformed()).isFalse();
        assertThat(r.getValue()).isEqualTo(1000.0);
    }

    // ---------------------------------------------------------------
    // parseAll(): whole-response handling
    // ---------------------------------------------------------------

    @Test
    void parseAllReturnsSingleMalformedReadingWhenApiReportsNonZeroCode() {
        JsonNode root = json("""
            {
              "code": 1,
              "errorMsg": "upstream unavailable",
              "data": { "items": [] }
            }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(1);
        Reading r = readings.get(0);
        assertThat(r.isMalformed()).isTrue();
        assertThat(r.getRegion()).isEqualTo(REGION);
        assertThat(r.getSourceId()).isEqualTo(SOURCE_ID);
        assertThat(r.getRawPayload()).isEqualTo(root.toString());
        assertThat(r.getObservedAt()).isNull();
    }

    @Test
    void parseAllReturnsEmptyListWhenItemsArrayIsEmpty() {
        JsonNode root = json("""
            { "code": 0, "data": { "items": [] } }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).isEmpty();
    }

    @Test
    void parseAllReturnsSingleMalformedReadingWhenItemsKeyIsAbsent() {
        // "data" exists but has no "items" array: response shape changed (S1-4.5)
        JsonNode root = json("""
            { "code": 0, "data": {} }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).isMalformed()).isTrue();
        assertThat(readings.get(0).getRawPayload()).isEqualTo(root.toString());
    }

    @Test
    void parseAllReturnsSingleMalformedReadingWhenDataKeyIsAbsent() {
        JsonNode root = json("""
            { "code": 0 }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).isMalformed()).isTrue();
        assertThat(readings.get(0).getRawPayload()).isEqualTo(root.toString());
    }

    @Test
    void parseAllReturnsSingleMalformedReadingWhenItemsIsNotAnArray() {
        JsonNode root = json("""
            { "code": 0, "data": { "items": "oops" } }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).isMalformed()).isTrue();
    }

    @Test
    void parseAllReturnsSingleMalformedReadingWhenCodeIsMissing() {
        // missing "code" is treated as a failed response, not as success
        JsonNode root = json("""
            { "data": { "items": [] } }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(1);
        assertThat(readings.get(0).isMalformed()).isTrue();
        assertThat(readings.get(0).getRawPayload()).isEqualTo(root.toString());
    }

    @Test
    void parseAllHandlesMixOfValidAndInvalidItemsPreservingOrder() {
        JsonNode root = json("""
            {
              "code": 0,
              "data": {
                "items": [
                  {
                    "timestamp": "2024-01-01T08:00:00+08:00",
                    "readings": { "pm25_one_hourly": { "central": 42.5 } }
                  },
                  {
                    "timestamp": "2024-01-01T09:00:00+08:00",
                    "readings": { "pm25_one_hourly": { "central": -1 } }
                  },
                  {
                    "timestamp": "not-a-date",
                    "readings": { "pm25_one_hourly": { "central": 10.0 } }
                  }
                ]
              }
            }
            """);

        List<Reading> readings = parser.parseAll(root, REGION, SOURCE_ID);

        assertThat(readings).hasSize(3);
        assertThat(readings.get(0).isMalformed()).isFalse();
        assertThat(readings.get(0).getValue()).isEqualTo(42.5);
        assertThat(readings.get(1).isMalformed()).isTrue(); // negative value
        assertThat(readings.get(2).isMalformed()).isTrue(); // bad timestamp

        // every reading carries the same source/region context regardless of validity
        readings.forEach(r -> {
            assertThat(r.getSourceId()).isEqualTo(SOURCE_ID);
            assertThat(r.getRegion()).isEqualTo(REGION);
        });
    }
}
