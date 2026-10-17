package com.cs203.healthwatch.detection;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the latest computed baseline from public.baselines. A baseline belongs to a data source, so it is found
 * through public.data_sources.type, the same way ReadingRepository finds readings. Any database error returns
 * "nothing found" and detection is skipped instead of failing.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BaselineRepository {

    private final JdbcTemplate jdbc;

    /** The most recently computed baseline for this region and source type. mad is stored already scaled by 1.4826. */
    public Optional<BaselineSnapshot> latestBaseline(String region, String signalType, String sourceType) {
        try {
            return jdbc.query("""
                            select b.baseline_value, b.mad from public.baselines b
                            join public.data_sources s on s.id = b.source_id
                            where b.region = ? and s.type = ?
                              and b.baseline_value is not null and b.mad is not null
                            order by b.computed_at desc nulls last
                            limit 1
                            """,
                    (rs, i) -> new BaselineSnapshot(region, signalType,
                            rs.getDouble("baseline_value"), rs.getDouble("mad")),
                    region, sourceType).stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("Could not read public.baselines: {}", e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }
}