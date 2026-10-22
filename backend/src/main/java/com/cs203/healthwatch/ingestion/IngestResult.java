package com.cs203.healthwatch.ingestion;

/**
 * What one response produced once stored. Used by the backfill to report totals; live pulls ignore it.
 *
 * @param saved      new rows written, valid or malformed
 * @param malformed  of the saved rows, how many were flagged malformed
 * @param duplicates rows skipped because that (source_id, region, observed_at) was already stored
 * @param failed     rows that could not be saved for another reason (logged)
 */
public record IngestResult(int saved, int malformed, int duplicates, int failed) {

    public static final IngestResult EMPTY = new IngestResult(0, 0, 0, 0);

    public IngestResult plus(IngestResult other) {
        return new IngestResult(saved + other.saved, malformed + other.malformed,
                duplicates + other.duplicates, failed + other.failed);
    }
}
