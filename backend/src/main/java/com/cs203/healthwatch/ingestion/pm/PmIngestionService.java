package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.ingestion.IngestionService;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties.Region;
import com.cs203.healthwatch.ingestion.IngestResult;
import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;

import java.time.OffsetDateTime;
import java.util.List;
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
        r.setIngestedAt(OffsetDateTime.now());
        try {
            repo.save(r);
            return SaveOutcome.SAVED;
        } catch (DataIntegrityViolationException e) {
            // Only unique constraint right now is (source_id, region, observed_at), so any
            // violation here means this exact reading was already stored.
            log.info("duplicate reading skipped: {} {}", r.getRegion(), r.getObservedAt());
            return SaveOutcome.DUPLICATE;
        } catch (RuntimeException e) {
            log.error("failed to save reading {} {}: {}", r.getRegion(), r.getObservedAt(), e.getMessage(), e);
            return SaveOutcome.FAILED;
        }
    }
}
