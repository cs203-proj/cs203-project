package com.cs203.healthwatch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.confidence.ConfidenceInputs;
import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.confidence.ConfidenceResult;
import com.cs203.healthwatch.detection.SpatialScope;
import com.cs203.healthwatch.dto.WarningAiSummary;
import com.cs203.healthwatch.dto.WarningAiSummary.AiStatus;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.service.WarningService;
import com.cs203.healthwatch.warnings.TestWarnings;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * CG-167: the review queue's AI summary (CG-165/166). Goes through the real WarningService and a real (H2) database,
 * because the thing under test is the joined query and the mapping on top of it.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class WarningQueueAiTest {

    @Autowired TestEntityManager em;
    @Autowired WarningRepository warningRepository;

    private WarningService service;

    @BeforeEach
    void service() {
        service = new WarningService(warningRepository);
    }

    // ------------------------------------------------------------------ fixture helpers

    /** An event with a confidence score, so the confidence columns have something to show. */
    private UUID eventWithConfidence(double score, ConfidenceLevel level) {
        DetectedEvent event = TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-10-14T01:00:00Z");
        event.applyConfidence(new ConfidenceInputs(0, 6, 4.2, 0.5), new ConfidenceResult(score, level, 1));
        return em.persistAndFlush(event).getId();
    }

    private UUID eventWithoutConfidence() {
        return em.persistAndFlush(TestEvents.event(4.2, EventStatus.UNDER_REVIEW, "2026-10-14T01:00:00Z")).getId();
    }

    private void explanation(UUID eventId, ExplanationStatus status) {
        em.persistAndFlush(explanationRow(eventId, status));
    }

    private EventExplanation explanationRow(UUID eventId, ExplanationStatus status) {
        EventExplanation x = new EventExplanation();
        x.setEventId(eventId);
        x.setStatus(status);
        x.setDataComplete(true);
        x.setDataQualityNote("Full data");
        if (status == ExplanationStatus.OK) {
            x.setAssessment(Assessment.GENUINE_TREND);
            x.setLikelyCause("Regional haze");
            x.setExplanation("A sustained rise.");
            x.setModel("test-model");
        } else {
            x.setError("Groq returned 500");
        }
        return x;
    }

    /** Persists a warning and pins created_at, which @CreationTimestamp would otherwise set to "now". */
    private Warning warning(UUID eventId, WarningSeverity severity, String createdAt) {
        Warning w = em.persistAndFlush(TestWarnings.warning(eventId, severity, WarningStatus.PENDING_REVIEW));
        em.getEntityManager().createNativeQuery("update warnings set created_at = :t where id = :id")
                .setParameter("t", Instant.parse(createdAt)).setParameter("id", w.getId()).executeUpdate();
        return w;
    }

    private WarningAiSummary aiOf(UUID warningId) {
        em.clear();
        return service.queue(null, 50, 0).items().stream()
                .filter(i -> i.id().equals(warningId)).findFirst().orElseThrow().ai();
    }

    // ------------------------------------------------------------------ OK / FAILED / missing

    @Test
    void okExplanation_fillsEveryField() {
        UUID event = eventWithConfidence(0.7425, ConfidenceLevel.HIGH);
        explanation(event, ExplanationStatus.OK);
        Warning w = warning(event, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.status()).isEqualTo(AiStatus.OK);
        assertThat(ai.assessment()).isEqualTo(Assessment.GENUINE_TREND);
        assertThat(ai.likelyCause()).isEqualTo("Regional haze");
        assertThat(ai.confidenceScore()).isEqualTo(0.7425);
        assertThat(ai.confidenceLevel()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void okExplanationWithScope_returnsScopeListsAndSummary() {
        UUID event = eventWithConfidence(0.7, ConfidenceLevel.HIGH);
        EventExplanation x = explanationRow(event, ExplanationStatus.OK);
        x.setScope(SpatialScope.MULTI_REGION);
        x.setRegionsAbove(List.of("central", "north"));
        x.setRegionsCompared(List.of("central", "north", "south"));
        x.setScopeSummary("Two of three reporting regions are above.");
        em.persistAndFlush(x);
        Warning w = warning(event, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.scope()).isEqualTo(SpatialScope.MULTI_REGION);
        assertThat(ai.regionsAbove()).containsExactly("central", "north");
        assertThat(ai.regionsCompared()).containsExactly("central", "north", "south");
        assertThat(ai.scopeSummary()).isEqualTo("Two of three reporting regions are above.");
    }

    @Test
    void failedExplanation_keepsTheScopeTheCodeComputed_butNoSummary() {
        UUID event = eventWithConfidence(0.5, ConfidenceLevel.MEDIUM);
        EventExplanation x = explanationRow(event, ExplanationStatus.FAILED);
        x.setScope(SpatialScope.LOCAL);
        x.setRegionsAbove(List.of("central"));
        x.setRegionsCompared(List.of("central", "east", "north"));
        em.persistAndFlush(x);
        Warning w = warning(event, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.status()).isEqualTo(AiStatus.UNAVAILABLE);
        assertThat(ai.scope()).isEqualTo(SpatialScope.LOCAL);
        assertThat(ai.regionsAbove()).containsExactly("central");
        assertThat(ai.scopeSummary()).isNull();
    }

    @Test
    void legacyRowWithNullScope_andMissingRow_readAsUnknownWithEmptyLists() {
        UUID legacy = eventWithConfidence(0.7, ConfidenceLevel.HIGH);
        explanation(legacy, ExplanationStatus.OK); // scope columns are null, as before V16
        Warning w1 = warning(legacy, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");
        UUID noRow = eventWithConfidence(0.3, ConfidenceLevel.LOW);
        Warning w2 = warning(noRow, WarningSeverity.HIGH, "2026-10-14T03:00:00Z");

        for (Warning w : List.of(w1, w2)) {
            WarningAiSummary ai = aiOf(w.getId());
            assertThat(ai.scope()).isEqualTo(SpatialScope.UNKNOWN);
            assertThat(ai.regionsAbove()).isEmpty();
            assertThat(ai.regionsCompared()).isEmpty();
        }
        assertThat(aiOf(w1.getId()).scopeSummary()).isNull();
    }

    @Test
    void failedExplanation_isUnavailable_withNoText_butKeepsConfidence() {
        UUID event = eventWithConfidence(0.5, ConfidenceLevel.MEDIUM);
        explanation(event, ExplanationStatus.FAILED);
        Warning w = warning(event, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.status()).isEqualTo(AiStatus.UNAVAILABLE);
        assertThat(ai.assessment()).isNull();
        assertThat(ai.likelyCause()).isNull();
        assertThat(ai.confidenceScore()).isEqualTo(0.5);
        assertThat(ai.confidenceLevel()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    void noExplanationRow_isUnavailable_andTheWarningIsStillListed() {
        UUID event = eventWithConfidence(0.3, ConfidenceLevel.LOW);
        Warning w = warning(event, WarningSeverity.HIGH, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.status()).isEqualTo(AiStatus.UNAVAILABLE);
        assertThat(ai.assessment()).isNull();
        assertThat(ai.confidenceScore()).isEqualTo(0.3);
    }

    @Test
    void eventWithNoConfidence_hasNullConfidence_andNoExplanation_isUnavailable() {
        UUID event = eventWithoutConfidence();
        Warning w = warning(event, WarningSeverity.LOW, "2026-10-14T02:00:00Z");

        WarningAiSummary ai = aiOf(w.getId());

        assertThat(ai.status()).isEqualTo(AiStatus.UNAVAILABLE);
        assertThat(ai.confidenceScore()).isNull();
        assertThat(ai.confidenceLevel()).isNull();
    }

    // ------------------------------------------------------------------ order and query count

    /** Mixed severities and ages, mixed explanation states: the joins must not change who comes first. */
    private void seed(int count) {
        WarningSeverity[] severities = WarningSeverity.values();
        for (int i = 0; i < count; i++) {
            UUID event = (i % 2 == 0) ? eventWithConfidence(0.6, ConfidenceLevel.MEDIUM) : eventWithoutConfidence();
            if (i % 3 == 0) {
                explanation(event, ExplanationStatus.OK);
            } else if (i % 3 == 1) {
                explanation(event, ExplanationStatus.FAILED);
            }
            warning(event, severities[i % severities.length], "2026-10-14T0" + (i % 9) + ":00:00Z");
        }
    }

    @Test
    void queueOrder_isIdenticalToTheOldQueueWithoutJoins() {
        seed(12);
        em.clear();

        List<UUID> old = warningRepository.findQueue(new OffsetPageRequest(0, 50)).getContent().stream()
                .map(Warning::getId).toList();
        List<UUID> joined = service.queue(null, 50, 0).items().stream().map(WarningResponse::id).toList();

        assertThat(joined).hasSize(12).containsExactlyElementsOf(old);
    }

    @Test
    void queryCount_doesNotGrowWithThePageSize() {
        Statistics stats = em.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class)
                .getStatistics();

        seed(6);
        em.clear();
        stats.clear();
        service.queue(null, 50, 0);
        long forSix = stats.getPrepareStatementCount();

        seed(12); // 18 warnings in total now
        em.clear();
        stats.clear();
        assertThat(service.queue(null, 50, 0).items()).hasSize(18);
        long forEighteen = stats.getPrepareStatementCount();

        // An N+1 would add one statement per warning. The joined query is one select, plus the count.
        assertThat(forEighteen).isEqualTo(forSix);
        assertThat(forSix).isLessThanOrEqualTo(2);
    }
}
