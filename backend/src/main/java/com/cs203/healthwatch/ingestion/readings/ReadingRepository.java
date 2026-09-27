package com.cs203.healthwatch.ingestion.readings;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReadingRepository extends JpaRepository<Reading, Long> {

    /**
     * Latest non-malformed readings for a region whose data source has the given type,
     * newest first. {@code synthetic} selects real readings (false) or replay readings (true).
     */
    @Query("""
            select r from Reading r
            where r.region = :region
              and r.malformed = false
              and r.synthetic = :synthetic
              and r.sourceId in (select d.id from DataSource d where d.type = :sourceType)
            order by r.observedAt desc
            """)
    List<Reading> findLatestValid(@Param("region") String region,
                                  @Param("sourceType") String sourceType,
                                  @Param("synthetic") boolean synthetic,
                                  Pageable pageable);
}
