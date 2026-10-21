package com.cs203.healthwatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.warnings.TestWarnings;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class WarningRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired WarningRepository warningRepository;

    private UUID eventId;

    @BeforeEach
    void event() {
        DetectedEvent event = em.persistAndFlush(
                TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-10-14T01:00:00Z"));
        eventId = event.getId();
    }

    /** Persists a warning and pins created_at, which @CreationTimestamp would otherwise set to "now". */
    private Warning persist(WarningSeverity severity, WarningStatus status, String createdAt) {
        Warning warning = em.persistAndFlush(TestWarnings.warning(eventId, severity, status));
        em.getEntityManager()
                .createNativeQuery("update warnings set created_at = :t where id = :id")
                .setParameter("t", Instant.parse(createdAt))
                .setParameter("id", warning.getId())
                .executeUpdate();
        em.clear();
        return warning;
    }

    private static OffsetPageRequest firstPage() {
        return new OffsetPageRequest(0, 50);
    }

    // ------------------------------------------------------------------ warnings table round trip

    @Test
    void savedWarningIsPendingReviewByDefault_andReadsBack() {
        Warning warning = new Warning();
        warning.setEventId(eventId);
        warning.setSeverity(WarningSeverity.HIGH);
        warning.setWarningText("PM2.5 is elevated in the central region.");

        UUID id = em.persistAndFlush(warning).getId();
        em.clear();

        Warning loaded = warningRepository.findById(id).orElseThrow();
        assertThat(loaded.getEventId()).isEqualTo(eventId);
        assertThat(loaded.getAudience()).isEqualTo("GENERAL_PUBLIC");
        assertThat(loaded.getSeverity()).isEqualTo(WarningSeverity.HIGH);
        assertThat(loaded.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
        assertThat(loaded.getWarningText()).isEqualTo("PM2.5 is elevated in the central region.");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ review queue (CG-29)

    @Test
    void sortsBySeverityRank_notAlphabetically() {
        persist(WarningSeverity.MEDIUM, WarningStatus.PENDING_REVIEW, "2026-10-14T01:00:00Z");
        persist(WarningSeverity.CRITICAL, WarningStatus.PENDING_REVIEW, "2026-10-14T02:00:00Z");
        persist(WarningSeverity.LOW, WarningStatus.PENDING_REVIEW, "2026-10-14T03:00:00Z");
        persist(WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW, "2026-10-14T04:00:00Z");

        Page<Warning> page = warningRepository.findQueue(WarningStatus.PENDING_REVIEW, firstPage());

        assertThat(page.getContent()).extracting(Warning::getSeverity).containsExactly(
                WarningSeverity.CRITICAL, WarningSeverity.HIGH, WarningSeverity.MEDIUM, WarningSeverity.LOW);
    }

    @Test
    void equalSeverityIsOrderedOldestFirst() {
        Warning newer = persist(WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW, "2026-10-14T05:00:00Z");
        Warning older = persist(WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW, "2026-10-14T01:00:00Z");

        Page<Warning> page = warningRepository.findQueue(WarningStatus.PENDING_REVIEW, firstPage());

        assertThat(page.getContent()).extracting(Warning::getId).containsExactly(older.getId(), newer.getId());
    }

    @Test
    void statusFilterExcludesReviewedWarnings() {
        persist(WarningSeverity.CRITICAL, WarningStatus.APPROVED, "2026-10-14T01:00:00Z");
        persist(WarningSeverity.CRITICAL, WarningStatus.SUPPRESSED, "2026-10-14T01:00:00Z");
        Warning pending = persist(WarningSeverity.LOW, WarningStatus.PENDING_REVIEW, "2026-10-14T02:00:00Z");

        Page<Warning> page = warningRepository.findQueue(WarningStatus.PENDING_REVIEW, firstPage());

        assertThat(page.getContent()).extracting(Warning::getId).containsExactly(pending.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void withoutFilterReturnsEveryStatus() {
        persist(WarningSeverity.HIGH, WarningStatus.APPROVED, "2026-10-14T01:00:00Z");
        persist(WarningSeverity.LOW, WarningStatus.PENDING_REVIEW, "2026-10-14T02:00:00Z");

        assertThat(warningRepository.findQueue(firstPage()).getTotalElements()).isEqualTo(2);
    }

    @Test
    void pagesDoNotOverlap_andTotalCountsAllPages() {
        persist(WarningSeverity.CRITICAL, WarningStatus.PENDING_REVIEW, "2026-10-14T01:00:00Z");
        persist(WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW, "2026-10-14T01:00:00Z");
        persist(WarningSeverity.MEDIUM, WarningStatus.PENDING_REVIEW, "2026-10-14T01:00:00Z");

        Page<Warning> second = warningRepository.findQueue(WarningStatus.PENDING_REVIEW, new OffsetPageRequest(1, 1));

        assertThat(second.getContent()).extracting(Warning::getSeverity).containsExactly(WarningSeverity.HIGH);
        assertThat(second.getTotalElements()).isEqualTo(3);
    }

    @Test
    void emptyQueueReturnsEmptyPage() {
        Page<Warning> page = warningRepository.findQueue(WarningStatus.PENDING_REVIEW, firstPage());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
