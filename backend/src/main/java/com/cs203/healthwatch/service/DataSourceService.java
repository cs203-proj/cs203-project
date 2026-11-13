package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.dto.UpdateDataSourceRequest;
import com.cs203.healthwatch.ingestion.IngestionService;
import com.cs203.healthwatch.ingestion.config.SourceUrlProperties;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import com.cs203.healthwatch.ingestion.sources.DataSourceRepository;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** SQLState for a unique-constraint violation (Postgres and H2). */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSourceRepository repo;
    /** name -> type of every source some IngestionService in this build can ingest. */
    private final Map<String, String> ingestibleTypes;
    private final Set<String> allowedHosts;

    public DataSourceService(DataSourceRepository repo, List<IngestionService> ingestionServices,
                             SourceUrlProperties urlProperties) {
        this.repo = repo;
        this.ingestibleTypes = ingestionServices.stream()
                .collect(Collectors.toUnmodifiableMap(IngestionService::sourceName, IngestionService::sourceType));
        this.allowedHosts = urlProperties.allowedHosts().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
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
        String type = request.type().trim();
        if (repo.existsByName(name)) {
            throw duplicateName(name);
        }
        String expectedType = ingestibleTypes.get(name);
        if (expectedType != null && !expectedType.equals(type)) {
            throw new BadRequestException("Data source " + name + " is ingested as type '" + expectedType
                    + "', not '" + type + "'");
        }
        DataSource source = new DataSource(name, type);
        source.setUrl(checkedUrl(request.url()));
        // Registered sources start off: when their ingestion code ships, nothing is pulled until an admin has
        // checked the url and enabled the source. Sources created by the ingestion code itself start enabled.
        source.setEnabled(false);
        try {
            source = repo.saveAndFlush(source);
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw duplicateName(name);   // another request created the same name between the check and insert
            }
            throw e;                          // any other constraint is a real failure, not a duplicate
        }
        if (isIngestible(source)) {
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
        source.setUrl(checkedUrl(request.url()));
        // never log the url itself: it may carry an api key or token in its query string
        log.info("data source {} url {}", source.getName(), source.getUrl() == null ? "cleared" : "updated");
        return toResponse(source);
    }

    @Transactional
    public DataSourceResponse setEnabled(UUID id, boolean enabled) {
        DataSource source = find(id);
        source.setEnabled(enabled);
        log.info("data source {} {}", source.getName(), enabled ? "enabled" : "disabled");
        return toResponse(source);
    }

    /**
     * Blank means "no url" (ingestion uses the configured default). Otherwise the url must be https on an allowed
     * host with the default port and no user info, so the server cannot be pointed at localhost, private or
     * cloud-metadata addresses (SSRF). The bean-validation check on the request already rejects malformed urls.
     */
    private String checkedUrl(String raw) {
        String url = blankToNull(raw);
        if (url == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("url is not a valid URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean ok = "https".equalsIgnoreCase(uri.getScheme())
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && allowedHosts.contains(host);
        if (!ok) {
            throw new BadRequestException("url must be https on an allowed host: " + allowedHosts);
        }
        return url;
    }

    private boolean isIngestible(DataSource source) {
        return source.getType().equals(ingestibleTypes.get(source.getName()));
    }

    private DataSourceResponse toResponse(DataSource source) {
        return DataSourceResponse.from(source, isIngestible(source));
    }

    private DataSource find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Data source " + id + " not found"));
    }
    
    private static ConflictException duplicateName(String name) {
        return new ConflictException("A data source named '" + name + "' already exists");
    }

    /** True only if the database reported a unique-constraint violation (the only unique column is name). */
    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return UNIQUE_VIOLATION.equals(sql.getSQLState());
            }
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
