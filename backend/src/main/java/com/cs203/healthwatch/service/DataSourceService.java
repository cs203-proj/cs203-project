package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.dto.UpdateDataSourceRequest;
import com.cs203.healthwatch.ingestion.IngestionService;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import com.cs203.healthwatch.ingestion.sources.DataSourceRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of data sources (CG-58). The database decides which sources exist, their url and whether they
 * are on; the code (one {@link IngestionService} per feed) decides how a feed is parsed. A source registered before
 * its ingestion code exists is stored and reported as ingestionAvailable = false; once the code is deployed it picks
 * up the existing row by name, with its url and enabled flag.
 *
 * There is no delete: readings reference their source, so a source is disabled instead and its history is kept.
 */
@Service
public class DataSourceService {
    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceRepository repo;
    /** Names of the sources some IngestionService in this build can ingest. */
    private final Set<String> ingestibleNames;

    public DataSourceService(DataSourceRepository repo, List<IngestionService> ingestionServices) {
        this.repo = repo;
        this.ingestibleNames = ingestionServices.stream().map(IngestionService::sourceName)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** All sources sorted by name, or only the one with this exact name (empty list if none). */
    @Transactional(readOnly = true)
    public List<DataSourceResponse> list(String name) {
        if (name != null && !name.isBlank()) {
            return repo.findByName(name.trim()).map(this::toResponse).stream().toList();
        }
        return repo.findAll(Sort.by("name")).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DataSourceResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional
    public DataSourceResponse create(CreateDataSourceRequest request) {
        String name = request.name().trim();
        if (repo.existsByName(name)) {
            throw new ConflictException("A data source named '" + name + "' already exists");
        }
        DataSource source = new DataSource(name, request.type().trim());
        source.setUrl(blankToNull(request.url()));
        // Registered sources start off: when their ingestion code ships, nothing is pulled until an admin has
        // checked the url and enabled the source. Sources created by the ingestion code itself start enabled.
        source.setEnabled(false);
        try {
            source = repo.saveAndFlush(source);
        } catch (DataIntegrityViolationException e) {
            // another request created the same name between the check and the insert
            throw new ConflictException("A data source named '" + name + "' already exists");
        }
        boolean available = ingestibleNames.contains(source.getName());
        if (available) {
            log.info("data source {} created ({})", source.getName(), source.getId());
        } else {
            log.warn("data source {} created ({}) but no ingestion code handles it yet; it will not be pulled",
                    source.getName(), source.getId());
        }
        return toResponse(source);
    }

    @Transactional
    public DataSourceResponse update(UUID id, UpdateDataSourceRequest request) {
        DataSource source = find(id);
        source.setUrl(blankToNull(request.url()));
        log.info("data source {} url set to {}", source.getName(), source.getUrl());
        return toResponse(source);
    }

    @Transactional
    public DataSourceResponse setEnabled(UUID id, boolean enabled) {
        DataSource source = find(id);
        source.setEnabled(enabled);
        log.info("data source {} {}", source.getName(), enabled ? "enabled" : "disabled");
        return toResponse(source);
    }

    private DataSourceResponse toResponse(DataSource source) {
        return DataSourceResponse.from(source, ingestibleNames.contains(source.getName()));
    }

    private DataSource find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Data source " + id + " not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
