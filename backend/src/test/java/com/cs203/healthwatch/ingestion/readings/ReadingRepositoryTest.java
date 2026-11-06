package com.cs203.healthwatch.ingestion.readings;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.ingestion.readings.Reading;
import com.cs203.healthwatch.ingestion.readings.ReadingRepository;
import com.cs203.healthwatch.ingestion.sources.DataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * The CG-136 queries on public.readings: readings from the other regions around an event (handed over to CG-16
 * for source agreement), and the lookup ingestion uses to replace a malformed row.
 *
 * <p>Uses the test profile's H2 URL instead of @DataJpaTest's replacement database: readings has a column named
 * "value", a reserved word in H2 2.x, and only the profile's URL sets NON_KEYWORDS=VALUE. Without it the table is
 * never created.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("ReadingRepository (CG-136 queries)")
class ReadingRepositoryTest {

    private static final String TYPE = "air_quality";
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-14T08:00:00+08:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-14T09:30:00+08:00");

    @Autowired TestEntityManager em;
    @Autowired ReadingRepository readingRepository;

    private UUID pmSource;
    private UUID otherTypeSource;

    @BeforeEach
    void sources() {
        pmSource = em.persistAndFlush(new DataSource("datagovsg_pm25", TYPE)).getId();
        otherTypeSource = em.persistAndFlush(new DataSource("some_psi_feed", "psi")).getId();
    }

    private Reading persist(UUID sourceId, String region, String observedAt, double value,
                            boolean malformed, boolean synthetic) {
        Reading r = new Reading();
        r.setSourceId(sourceId);
        r.setRegion(region);
        r.setObservedAt(OffsetDateTime.parse(observedAt));
        r.setValue(value);
        r.setMalformed(malformed);
        r.setSynthetic(synthetic);
        return em.persistAndFlush(r);
    }

    private Reading valid(String region, String observedAt, double value) {
        return persist(pmSource, region, observedAt, value, false, false);
    }

    private List<Reading> othersThan(String region) {
        return readingRepository.findOtherRegionsBetween(region, TYPE, FROM, TO);
    }

    @Test
    @DisplayName("Returns only valid, real readings of the same type from the other regions")
    void findOtherRegionsBetween_ShouldReturnOnlyComparableReadings_WhenMixedReadingsExist() {
        // Arrange: one reading that qualifies, and one excluded reading per rule
        valid("north", "2026-10-14T09:00:00+08:00", 20);
        valid("central", "2026-10-14T09:00:00+08:00", 99);                                // the event's own region
        persist(pmSource, "south", "2026-10-14T09:00:00+08:00", 30, true, false);          // malformed
        persist(pmSource, "east", "2026-10-14T09:00:00+08:00", 40, false, true);           // synthetic (replay)
        persist(otherTypeSource, "west", "2026-10-14T09:00:00+08:00", 50, false, false);   // another signal

        // Act
        List<Reading> result = othersThan("central");

        // Assert
        assertThat(result).extracting(Reading::getRegion).containsExactly("north");
    }

    @Test
    @DisplayName("The window excludes its start and includes its end")
    void findOtherRegionsBetween_ShouldExcludeFromAndIncludeTo_WhenReadingsSitOnTheEdges() {
        // Arrange: boundary values of the (from, to] window
        valid("north", "2026-10-14T08:00:00+08:00", 1);   // exactly at from: excluded
        valid("south", "2026-10-14T09:30:00+08:00", 2);   // exactly at to: included
        valid("east", "2026-10-14T09:30:01+08:00", 3);    // just after to: excluded

        // Act
        List<Reading> result = othersThan("central");

        // Assert
        assertThat(result).extracting(Reading::getRegion).containsExactly("south");
    }

    @Test
    @DisplayName("Readings are grouped by region, newest first, so the caller can take the first per region")
    void findOtherRegionsBetween_ShouldOrderByRegionThenNewestFirst_WhenRegionsHaveSeveralReadings() {
        // Arrange
        valid("west", "2026-10-14T08:30:00+08:00", 1);
        valid("north", "2026-10-14T08:30:00+08:00", 2);
        valid("north", "2026-10-14T09:30:00+08:00", 3);
        valid("west", "2026-10-14T09:30:00+08:00", 4);

        // Act
        List<Reading> result = othersThan("central");

        // Assert
        assertThat(result).extracting(Reading::getValue).containsExactly(3.0, 2.0, 4.0, 1.0);
    }

    @Test
    @DisplayName("Finds the stored row for a source, region and hour")
    void findBySourceIdAndRegionAndObservedAt_ShouldReturnStoredRow_WhenItExists() {
        // Arrange
        Reading stored = persist(pmSource, "north", "2026-10-14T09:00:00+08:00", 20, true, false);
        em.clear();

        // Act
        var found = readingRepository.findBySourceIdAndRegionAndObservedAt(
                pmSource, "north", OffsetDateTime.parse("2026-10-14T09:00:00+08:00"));

        // Assert
        assertThat(found).map(Reading::getId).contains(stored.getId());
    }
}
