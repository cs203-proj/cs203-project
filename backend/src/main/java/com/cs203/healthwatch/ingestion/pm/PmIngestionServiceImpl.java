package com.cs203.healthwatch.ingestion.pm;

import com.cs203.healthwatch.ingestion.IngestionService;
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
public class PmIngestionServiceImpl implements IngestionService {
    private static final Logger log = LoggerFactory.getLogger(PmIngestionServiceImpl.class);

    private final FetchClient fetchClient;
    private final PmParser parser;
    private final ReadingRepository repo;
    private final ObjectMapper objectMapper;
    private final PmSourceProperties sourceProperties;
    private final DataSourceRegistry dataSourceRegistry;

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

        UUID sourceId = dataSourceRegistry.resolve(sourceProperties.name(), sourceProperties.type());
        List<Reading> readings = parser.parseAll(root, sourceProperties.region(), sourceId);

        for (Reading r : readings) {
            r.setIngestedAt(OffsetDateTime.now());
            try {
                repo.save(r);
            } catch (DataIntegrityViolationException e) {
                if (r.isMalformed()) {
                    log.warn("malformed reading lost to timestamp collision: {} {}", r.getRegion(), r.getObservedAt());
                } else {
                    log.info("duplicate reading skipped: {} {}", r.getRegion(), r.getObservedAt());
                }
                // currently infer the failure reason from the reading's own malformed flag 
                // rather than inspecting the DB exception, because only have one unique 
                // constraint currently. 

                // this doesn't generalise but if add more constraints later
                // either inspect the constraint name in the exception or 
                // simplify the log message to not guess a cause

            } catch (RuntimeException e) {
                log.error("failed to save reading {} {}: {}", r.getRegion(), r.getObservedAt(), e.getMessage(), e);
            }
        }
    }
}
