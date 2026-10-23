package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.ingestion.IngestionService;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties.Region;
import com.cs203.healthwatch.ingestion.IngestResult;
import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PmIngestionService implements IngestionService {
    private static final Logger log = LoggerFactory.getLogger(PmIngestionService.class);

    private final FetchClient fetchClient;
    private final PmParser parser;
    private final ReadingRepository repo;
    private final ObjectMapper objectMapper;
    private final PmSourceProperties sourceProperties;
    private final DataSourceRegistry dataSourceRegistry;
    private final Clock clock;

    /** SQLState for a unique-constraint violation (Postgres and H2). */
    private static final String UNIQUE_VIOLATION = "23505";

    private enum SaveOutcome {
        SAVED, DUPLICATE, FAILED
    }

    @Override
    public void ingestLatest() {
        String json = fetchClient.fetch(sourceProperties.url());
        if (json == null) {
            log.error("pull failed after retries, skipping this cycle");
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("failed to parse response as JSON: {}", e.getMessage());
            return;
        }

        ingest(root);
    }

    IngestResult ingest(JsonNode root) {
        UUID sourceId = dataSourceRegistry.resolve(sourceProperties.name(), sourceProperties.type());

        if (!parser.isValidResponse(root)) {
            log.error("response from {} is unusable, storing it as malformed", sourceProperties.name());
            Reading reading = parser.malformed(root, PmParser.ALL_REGIONS, sourceId);
            return tally(List.of(reading));
        }

        IngestResult total = IngestResult.EMPTY;

        for (Region r : sourceProperties.regions()) {
            try {
                total = total.plus(ingestRegion(root, sourceId, r));
            } catch (RuntimeException e) {
                log.error("ingestion failed for source {} region {}: {}",
                        sourceProperties.name(), r.name(), e.getMessage(), e);
            }
        }

        return total;

    }

    private IngestResult ingestRegion(JsonNode root, UUID sourceId, Region region) {
        List<Reading> readings = parser.parseAll(root, region.name(), sourceId);

        if (readings.isEmpty()) {
            log.info("response contained no items for region {}", region.name());
        } else {
            List<String> inFeed = parser.regionsInFeed(root);
            if (!inFeed.contains(region.name())) {
                log.warn("region '{}' is configured in pm.regions but is not in the {} payload (feed has {}); "
                                + "its readings are stored as malformed until the config or the feed is fixed",
                        region.name(), sourceProperties.name(), inFeed);
            }
        }

        return tally(readings);
    }

    private IngestResult tally(List<Reading> readings) {
        int saved = 0;
        int malformed = 0;
        int duplicates = 0;
        int failed = 0;
        for (Reading r : readings) {
            switch (save(r)) {
                case SAVED -> {
                    saved++;
                    if (r.isMalformed()) {
                        malformed++;
                    }
                }
                case DUPLICATE -> duplicates++;
                case FAILED -> failed++;
            }
        }
        return new IngestResult(saved, malformed, duplicates, failed);
    }

    private SaveOutcome save(Reading r) {
        r.setIngestedAt(OffsetDateTime.now(clock));
        try {
            repo.save(r);
            return SaveOutcome.SAVED;
        } catch (DataIntegrityViolationException e) {
            if (isOtherConstraintViolation(e)) {
                // e.g. NOT NULL or foreign key: a real failure, not a reading we already have
                log.error("failed to save reading {} {}: {}", r.getRegion(), r.getObservedAt(),
                        e.getMostSpecificCause().getMessage(), e);
                return SaveOutcome.FAILED;
            }
            return repairPreviouslyMalformed(r);
        } catch (RuntimeException e) {
            log.error("failed to save reading {} {}: {}", r.getRegion(), r.getObservedAt(), e.getMessage(), e);
            return SaveOutcome.FAILED;
        }
    }

    /**
     * The (source_id, region, observed_at) row already exists. Malformed readings now keep their timestamp, so if
     * that row was stored malformed and the feed now has a valid value for the same hour, replace it rather than
     * lose the valid reading. Otherwise it is an ordinary duplicate and is skipped.
     */
    private SaveOutcome repairPreviouslyMalformed(Reading incoming) {
        Optional<Reading> existing = (incoming.isMalformed() || incoming.getObservedAt() == null)
                ? Optional.empty()
                : repo.findBySourceIdAndRegionAndObservedAt(
                        incoming.getSourceId(), incoming.getRegion(), incoming.getObservedAt());
        if (existing.isEmpty() || !existing.get().isMalformed()) {
            log.info("duplicate reading skipped: {} {}", incoming.getRegion(), incoming.getObservedAt());
            return SaveOutcome.DUPLICATE;
        }
        Reading stored = existing.get();
        stored.setValue(incoming.getValue());
        stored.setRawPayload(incoming.getRawPayload());
        stored.setMalformed(false);
        stored.setIngestedAt(incoming.getIngestedAt());
        try {
            repo.save(stored);
            log.info("reading {} {} was malformed and is now valid; replaced", stored.getRegion(),
                    stored.getObservedAt());
            return SaveOutcome.SAVED;
        } catch (RuntimeException e) {
            log.error("failed to replace malformed reading {} {}: {}", stored.getRegion(), stored.getObservedAt(),
                    e.getMessage(), e);
            return SaveOutcome.FAILED;
        }
    }

    /** True only if the database reported a constraint other than a unique one. */
    private static boolean isOtherConstraintViolation(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return !UNIQUE_VIOLATION.equals(sql.getSQLState());
            }
        }
        return false;
    }
}
