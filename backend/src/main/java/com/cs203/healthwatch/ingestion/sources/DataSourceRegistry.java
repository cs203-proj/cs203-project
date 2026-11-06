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
                .map(DataSource::getId)
                .orElseGet(() -> createSource(name, type));

        cache.put(name, resolved);
        return resolved;
    }

    /**
     * The source row for a feed, read from the database on every call (only the id is cached), so an admin's
     * enable/disable or url change takes effect on the next pull.
     */
    public DataSource current(String name, String type) {
        UUID id = resolve(name, type);
        return repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("data source " + name + " (" + id + ") not found"));
    }

    UUID createSource(String name, String type) {
        try {
            return repo.save(new DataSource(name, type)).getId();
        } catch (DataIntegrityViolationException e) {
            return repo.findByName(name).map(DataSource::getId).orElseThrow(() -> e);
        }
    }
}