package com.cs203.healthwatch.ingestion.readings;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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


    @Query("""
          select r from Reading r
          where r.region = :region
          and r.malformed = false
          and r.value is not null
          and r.observedAt >= :from and r. observedAt < :to
          and r.sourceId in (select d.id from DataSource d where d. type = :sourceType)
          order by r.observedAt asc
    """)
    List<Reading> findValidBetween(@Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /**
     * The newest valid readings observed at or before {@code to}, newest first (CG-152: rebuilds what detection saw
     * when an event was raised). Live events use real readings only; replay events also accept synthetic ones, like
     * the replay window query above.
     */
    @Query("""
          select r from Reading r
          where r.region = :region
          and r.malformed = false
          and r.value is not null
          and (:includeSynthetic = true or r.synthetic = false)
          and r.observedAt <= :to
          and r.sourceId in (select d.id from DataSource d where d.type = :sourceType)
          order by r.observedAt desc
    """)
    List<Reading> findValidUpTo(@Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("includeSynthetic") boolean includeSynthetic,
            @Param("to") OffsetDateTime to,
            Pageable pageable);

    /**
     * Valid real readings from every region except {@code region}, in the window {@code (from, to]}, grouped by
     * region and newest first within each region. CG-136 hand-over for CG-16: lets source agreement check whether
     * a spike in one region also shows up in the others (a "source" is a (source_id, region) pair).
     */
    @Query("""
          select r from Reading r
          where r.region <> :region
          and r.malformed = false
          and r.synthetic = false
          and r.value is not null
          and r.observedAt > :from and r.observedAt <= :to
          and r.sourceId in (select d.id from DataSource d where d.type = :sourceType)
          order by r.region asc, r.observedAt desc
    """)
    List<Reading> findOtherRegionsBetween(@Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    Optional<Reading> findBySourceIdAndRegionAndObservedAt(UUID sourceId, String region, OffsetDateTime observedAt);

    /**
     * Valid readings from every region except {@code region} in the window {@code (from, to]} (CG-169: cross-region
     * scope of an explained event). Like {@link #findValidUpTo}, replay events also accept synthetic readings.
     */
    @Query("""
          select r from Reading r
          where r.region <> :region
          and r.malformed = false
          and r.value is not null
          and (:includeSynthetic = true or r.synthetic = false)
          and r.observedAt > :from and r.observedAt <= :to
          and r.sourceId in (select d.id from DataSource d where d.type = :sourceType)
    """)
    List<Reading> findOtherRegionsValidBetween(@Param("region") String region,
            @Param("sourceType") String sourceType,
            @Param("includeSynthetic") boolean includeSynthetic,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
