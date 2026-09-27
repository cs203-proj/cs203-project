package com.cs203.healthwatch.detection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the baseline_dev schema (CG-62's dev workspace in Supabase), which holds the computed baselines and the
 * historical PM2.5 readings used for Demo 1. Temporary until public.readings/public.baselines match this shape
 * (CG-2). The schema does not exist in a local Docker database, so any database error returns "nothing found"
 * and detection is skipped instead of failing.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BaselineDevRepository {

    private final JdbcTemplate jdbc;

    /** The most recently computed baseline for this region and signal. mad is stored already scaled by 1.4826. */
    public Optional<BaselineSnapshot> latestBaseline(String region, String signalType) {
        try {
            return jdbc.query("""
                            select median, mad from baseline_dev.baselines
                            where region = ? and signal_type = ? and median is not null and mad is not null
                            order by computed_at desc nulls last
                            limit 1
                            """,
                    (rs, i) -> new BaselineSnapshot(region, signalType, rs.getDouble("median"), rs.getDouble("mad")),
                    region, signalType).stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("Could not read baseline_dev.baselines: {}", e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    /** Valid readings in [from, to), oldest first. */
    public List<ReadingSnapshot> readingsBetween(String region, String signalType, Instant from, Instant to) {
        try {
            return jdbc.query("""
                            select value, observed_at from baseline_dev.readings
                            where region = ? and signal_type = ?
                              and observed_at >= ? and observed_at < ?
                              and coalesce(is_malformed, false) = false
                              and value is not null
                            order by observed_at
                            """,
                    (rs, i) -> new ReadingSnapshot(region, signalType, rs.getDouble("value"),
                            rs.getObject("observed_at", OffsetDateTime.class).toInstant()),
                    region, signalType, from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));
        } catch (DataAccessException e) {
            log.warn("Could not read baseline_dev.readings: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }
}
