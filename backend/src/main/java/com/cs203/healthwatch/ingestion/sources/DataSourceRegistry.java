package com.cs203.healthwatch.ingestion.sources;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DataSourceRegistry {

    private final DataSourceRepository repo;
    private final ConcurrentHashMap<String, UUID> cache = new ConcurrentHashMap<>();

    public UUID resolve(String name, String type) {
        UUID cached = cache.get(name);
        if (cached != null) return cached;

        UUID resolved = repo.findByName(name)
                .map(existing -> checkType(existing, type))
                .orElseGet(() -> createSource(name, type));

        cache.put(name, resolved);
        return resolved;
    }

    /**
     * The source row for a feed, read from the database on every call (only the id is cached), so an admin's
     * enable/disable, url change and type checks takes effect on the next pull.
     */
    public DataSource current(String name, String type) {
        UUID id = resolve(name, type);
        DataSource row = repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("data source " + name + " (" + id + ") not found"));
        checkType(row, type);   // checked on every pull, not only when the id is first cached
        return row;
    }
    
    /**
     * Detection finds readings by their source's type, so a row whose type differs from what the ingestion code
     * expects would silently hide its readings. Fail fast instead; the admin API rejects creating such a row.
     */
    private static UUID checkType(DataSource existing, String expectedType) {
        if (!existing.getType().equals(expectedType)) {
            throw new IllegalStateException("data source " + existing.getName() + " has type '" + existing.getType()
                    + "' but its ingestion code expects '" + expectedType + "'");
        }
        return existing.getId();
    }

    UUID createSource(String name, String type) {
        try {
            return repo.save(new DataSource(name, type)).getId();
        } catch (DataIntegrityViolationException e) {
            return repo.findByName(name).map(DataSource::getId).orElseThrow(() -> e);
        }
    }
}