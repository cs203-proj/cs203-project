package com.cs203.healthwatch.ingestion;

/** Code that knows how to pull and parse one feed. Each implementation ingests one data source. */
public interface IngestionService {
    void ingestLatest();

    /** data_sources.name of the source this service ingests, e.g. datagovsg_pm25. */
    String sourceName();
}