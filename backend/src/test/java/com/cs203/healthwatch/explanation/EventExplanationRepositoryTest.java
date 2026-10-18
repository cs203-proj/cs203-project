package com.cs203.healthwatch.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/** The entity round-trips through a real (in-memory) database, including the JSON list columns. */
@DataJpaTest
@ActiveProfiles("test")
class EventExplanationRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired EventExplanationRepository repo;

    private EventExplanation row(DetectedEvent event, ExplanationStatus status) {
        EventExplanation e = new EventExplanation();
        e.setEventId(event.getId());
        e.setStatus(status);
        e.setDataComplete(false);
        e.setDataQualityNote("Based on partial data: 4 of 6 expected readings in the last 6 hours.");
        e.setModel("gemini-test-model");
        return e;
    }

    @Test
    void storesAndReadsBackEveryField() {
        DetectedEvent event = em.persistAndFlush(TestEvents.event(6.1, EventStatus.NEW, "2026-10-18T03:00:00Z"));
        EventExplanation e = row(event, ExplanationStatus.OK);
        e.setAssessment(Assessment.LIKELY_NOISE);
        e.setLikelyCause("Single sensor jump");
        e.setExplanation("x".repeat(2000)); // longer than a default varchar
        e.setEvidence(List.of("One reading of 400 between readings of 20", "No rise at neighbouring times"));
        e.setAssumptions(List.of("Other stations are not reporting"));
        repo.saveAndFlush(e);
        em.clear();

        EventExplanation loaded = repo.findByEventId(event.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ExplanationStatus.OK);
        assertThat(loaded.getAssessment()).isEqualTo(Assessment.LIKELY_NOISE);
        assertThat(loaded.getExplanation()).hasSize(2000);
        assertThat(loaded.getEvidence()).containsExactly("One reading of 400 between readings of 20",
                "No rise at neighbouring times");
        assertThat(loaded.getAssumptions()).containsExactly("Other stations are not reporting");
        assertThat(loaded.isDataComplete()).isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(repo.existsByEventId(event.getId())).isTrue();
    }

    @Test
    void failedRow_keepsTheErrorAndNoAiFields() {
        DetectedEvent event = em.persistAndFlush(TestEvents.event(4.0, EventStatus.NEW, "2026-10-18T03:00:00Z"));
        EventExplanation e = row(event, ExplanationStatus.FAILED);
        e.setError("Gemini call failed: 429 Too Many Requests");
        repo.saveAndFlush(e);
        em.clear();

        EventExplanation loaded = repo.findByEventId(event.getId()).orElseThrow();
        assertThat(loaded.getError()).contains("429");
        assertThat(loaded.getAssessment()).isNull();
        assertThat(loaded.getEvidence()).isNull();
    }

    @Test
    void onlyOneExplanationPerEvent() {
        DetectedEvent event = em.persistAndFlush(TestEvents.event(4.0, EventStatus.NEW, "2026-10-18T03:00:00Z"));
        repo.saveAndFlush(row(event, ExplanationStatus.FAILED));

        assertThatThrownBy(() -> repo.saveAndFlush(row(event, ExplanationStatus.OK)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
