package com.cs203.healthwatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.model.AuditLog;
import com.cs203.healthwatch.model.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

/** Runs the real repository queries (sorting, filtering, paging) against an in-memory database. */
@DataJpaTest
@ActiveProfiles("test")
class EventRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired EventRepository eventRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private DetectedEvent persist(double deviation, EventStatus status, String detectedAt) {
        return em.persistAndFlush(TestEvents.event(deviation, status, detectedAt));
    }

    private static List<Double> deviations(Page<DetectedEvent> page) {
        return page.getContent().stream().map(DetectedEvent::getDeviation).toList();
    }

    @Test
    void sortsByDeviationLargestFirst() {
        persist(3.1, EventStatus.NEW, "2026-09-24T01:00:00Z");
        persist(5.7, EventStatus.UNDER_REVIEW, "2026-09-24T03:00:00Z");
        persist(4.2, EventStatus.DISMISSED, "2026-09-24T04:00:00Z");

        Page<DetectedEvent> page = eventRepository.findAllForList(new OffsetPageRequest(0, 50));

        assertThat(deviations(page)).containsExactly(5.7, 4.2, 3.1);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void equalDeviationsAreOrderedNewestFirst() {
        DetectedEvent older = persist(2.0, EventStatus.NEW, "2026-09-24T01:00:00Z");
        DetectedEvent newer = persist(2.0, EventStatus.NEW, "2026-09-24T05:00:00Z");

        Page<DetectedEvent> page = eventRepository.findAllForList(new OffsetPageRequest(0, 50));

        assertThat(page.getContent()).extracting(DetectedEvent::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void statusFilterReturnsOnlyMatchingEvents_stillSorted() {
        persist(1.0, EventStatus.NEW, "2026-09-24T01:00:00Z");
        persist(9.0, EventStatus.CONFIRMED, "2026-09-24T01:00:00Z");
        persist(3.0, EventStatus.NEW, "2026-09-24T01:00:00Z");

        Page<DetectedEvent> page = eventRepository.findAllForList(EventStatus.NEW, new OffsetPageRequest(0, 50));

        assertThat(deviations(page)).containsExactly(3.0, 1.0);
        assertThat(page.getContent()).allMatch(e -> e.getStatus() == EventStatus.NEW);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void emptyTableGivesEmptyPage() {
        Page<DetectedEvent> page = eventRepository.findAllForList(new OffsetPageRequest(0, 50));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void pagingWithAnyOffsetWalksTheWholeListWithoutGapsOrOverlap() {
        // Lots of ties, so only the full tie-break (deviation, detected_at, id) keeps pages consistent.
        for (int i = 0; i < 7; i++) {
            persist(i % 2 == 0 ? 2.0 : 1.0, EventStatus.NEW, "2026-09-24T0" + (i % 3) + ":00:00Z");
        }
        List<UUID> everything = eventRepository.findAllForList(new OffsetPageRequest(0, 50))
                .getContent().stream().map(DetectedEvent::getId).toList();

        List<UUID> walked = new ArrayList<>();
        for (long offset = 0; offset < 7; offset += 3) {
            Page<DetectedEvent> page = eventRepository.findAllForList(new OffsetPageRequest(offset, 3));
            assertThat(page.getTotalElements()).isEqualTo(7);
            page.getContent().forEach(e -> walked.add(e.getId()));
        }
        assertThat(walked).containsExactlyElementsOf(everything);

        // An offset that is not a multiple of the limit starts exactly where asked.
        Page<DetectedEvent> fromOne = eventRepository.findAllForList(new OffsetPageRequest(1, 2));
        assertThat(fromOne.getContent()).extracting(DetectedEvent::getId).containsExactlyElementsOf(everything.subList(1, 3));

        // Past the end: empty page, total still reported.
        Page<DetectedEvent> pastEnd = eventRepository.findAllForList(new OffsetPageRequest(100, 2));
        assertThat(pastEnd.getContent()).isEmpty();
        assertThat(pastEnd.getTotalElements()).isEqualTo(7);
    }

    @Test
    void auditEntriesComeBackOldestFirst_withIdBreakingTies() {
        DetectedEvent event = persist(4.2, EventStatus.NEW, "2026-09-24T01:00:00Z");
        UUID actor = em.persistAndFlush(new User("admin", "hash", "admin")).getId();
        Instant same = Instant.parse("2026-09-24T05:00:00Z");

        AuditLog third = auditLogRepository.save(new AuditLog(event.getId(), actor, EventStatus.UNDER_REVIEW,
                EventStatus.CONFIRMED, same));
        AuditLog first = auditLogRepository.save(new AuditLog(event.getId(), actor, EventStatus.NEW,
                EventStatus.UNDER_REVIEW, Instant.parse("2026-09-24T04:00:00Z")));
        AuditLog fourth = auditLogRepository.save(new AuditLog(event.getId(), actor, EventStatus.CONFIRMED,
                EventStatus.DISMISSED, same));
        em.flush();

        assertThat(auditLogRepository.findByEventIdOrderByChangedAtAscIdAsc(event.getId()))
                .extracting(AuditLog::getId)
                .containsExactly(first.getId(), third.getId(), fourth.getId());
        assertThat(auditLogRepository.findByEventIdOrderByChangedAtAscIdAsc(UUID.randomUUID())).isEmpty();
    }
}
