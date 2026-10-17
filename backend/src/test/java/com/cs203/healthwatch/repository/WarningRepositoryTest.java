package com.cs203.healthwatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/** The warnings table round-trips through JPA; the review-queue query is tested in the CG-29 queue PR. */
@DataJpaTest
@ActiveProfiles("test")
class WarningRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired WarningRepository warningRepository;

    @Test
    void savedWarningIsPendingReviewByDefault_andReadsBack() {
        DetectedEvent event = em.persistAndFlush(
                TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-10-14T01:00:00Z"));
        Warning warning = new Warning();
        warning.setEventId(event.getId());
        warning.setSeverity(WarningSeverity.HIGH);
        warning.setWarningText("PM2.5 is elevated in the central region.");

        UUID id = em.persistAndFlush(warning).getId();
        em.clear();

        Warning loaded = warningRepository.findById(id).orElseThrow();
        assertThat(loaded.getEventId()).isEqualTo(event.getId());
        assertThat(loaded.getAudience()).isEqualTo("GENERAL_PUBLIC");
        assertThat(loaded.getSeverity()).isEqualTo(WarningSeverity.HIGH);
        assertThat(loaded.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
        assertThat(loaded.getWarningText()).isEqualTo("PM2.5 is elevated in the central region.");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }
}