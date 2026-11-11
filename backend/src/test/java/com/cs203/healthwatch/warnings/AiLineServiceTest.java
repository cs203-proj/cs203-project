package com.cs203.healthwatch.warnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.EventExplanationRepository;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.service.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CG-154: the "why this matters" line is built from the CG-135 explanation, only added where it belongs, and an AI
 * failure or a missing explanation never breaks anything.
 */
class AiLineServiceTest {

    private static final String LINE = "Fine particles at these levels can worsen breathing for people with heart "
            + "or lung disease, and the rise may be consistent with regional haze.";

    private final AiLineClient client = mock(AiLineClient.class);
    private final WarningRepository warnings = mock(WarningRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final EventExplanationRepository explanations = mock(EventExplanationRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private AiLineService service;
    private DetectedEvent event;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AiLineService(client, new AiLineProperties(true, 400), warnings, events, explanations, audit,
                tx);
        when(client.isConfigured()).thenReturn(true);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0))
                .doInTransaction(null));
        when(warnings.setAiLineIfPending(any(), anyString(), any())).thenReturn(1);

        event = TestEvents.event(9.4, EventStatus.CONFIRMED, "2026-09-24T03:15:00Z");
        event.setId(UUID.randomUUID());
        event.setRegion("east");
        event.setSignalType("pm25");
        event.setConfidenceLevel(ConfidenceLevel.MEDIUM);
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        explanation(ExplanationStatus.OK);
    }

    private void explanation(ExplanationStatus status) {
        EventExplanation e = new EventExplanation();
        e.setEventId(event.getId());
        e.setStatus(status);
        if (status == ExplanationStatus.OK) {
            e.setAssessment(Assessment.GENUINE_TREND);
            e.setLikelyCause("Consistent with regional haze");
        }
        when(explanations.findByEventId(event.getId())).thenReturn(Optional.of(e));
    }

    private Warning warning(Audience audience, WarningStatus status) {
        Warning w = new Warning();
        w.setId(UUID.randomUUID());
        w.setEventId(event.getId());
        w.setAudience(audience.code());
        w.setSeverity(WarningSeverity.HIGH);
        w.setStatus(status);
        w.setWarningText("draft");
        when(warnings.findById(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    void addsTheLineToAPendingVulnerableDraftAndAuditsIt() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        when(client.write(any())).thenReturn("  " + LINE + "\n");

        assertThat(service.addLine(w.getId())).contains(LINE);

        verify(warnings).setAiLineIfPending(eq(w.getId()), eq(LINE), any(Instant.class));
        verify(audit).recordBySystem(w.getId(), AiLineService.AI_LINE_ADDED, null, Map.of("aiLine", LINE));
    }

    @Test
    void sendsTheAudienceAndTheExplanationButNeverTheAdvice() {
        Warning w = warning(Audience.HEALTHCARE_WORKER, WarningStatus.PENDING_REVIEW);
        when(client.write(any())).thenReturn(LINE);

        service.addLine(w.getId());

        ArgumentCaptor<AiLineContext> sent = ArgumentCaptor.forClass(AiLineContext.class);
        verify(client).write(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new AiLineContext(Audience.HEALTHCARE_WORKER, "east", "pm25",
                WarningSeverity.HIGH, ConfidenceLevel.MEDIUM, Assessment.GENUINE_TREND,
                "Consistent with regional haze"));
        assertThat(AiLinePrompt.facts(sent.getValue()))
                .doesNotContain(WarningDrafter.recommendedAction(Audience.HEALTHCARE_WORKER, WarningSeverity.HIGH));
    }

    @Test
    void everyAudienceGetsALine() {
        for (Audience audience : Audience.values()) {
            Warning w = warning(audience, WarningStatus.PENDING_REVIEW);
            when(client.write(any())).thenReturn(LINE);

            assertThat(service.addLine(w.getId())).as(audience.name()).contains(LINE);
        }
    }

    @Test
    void missingExplanationMeansNoAiCallAndNoLine() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        when(explanations.findByEventId(event.getId())).thenReturn(Optional.empty());

        assertThat(service.addLine(w.getId())).isEmpty();

        verify(client, never()).write(any());
    }

    @Test
    void failedExplanationMeansNoAiCallAndNoLine() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        explanation(ExplanationStatus.FAILED);

        assertThat(service.addLine(w.getId())).isEmpty();

        verify(client, never()).write(any());
    }

    @Test
    void approvedOrSuppressedWarningNeverGetsALine() {
        Warning approved = warning(Audience.VULNERABLE, WarningStatus.APPROVED);
        Warning suppressed = warning(Audience.VULNERABLE, WarningStatus.SUPPRESSED);

        assertThat(service.addLine(approved.getId())).isEmpty();
        assertThat(service.addLine(suppressed.getId())).isEmpty();

        verify(client, never()).write(any());
    }

    @Test
    void warningApprovedWhileTheAiWasWritingKeepsNoLine() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        when(client.write(any())).thenReturn(LINE);
        when(warnings.setAiLineIfPending(any(), anyString(), any())).thenReturn(0);

        assertThat(service.addLine(w.getId())).isEmpty();

        verifyNoInteractions(audit);
    }

    @Test
    void aiFailureIsSwallowedAndNothingIsWritten() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        when(client.write(any())).thenThrow(new IllegalStateException("Groq call failed: 429"));

        assertThat(service.addLine(w.getId())).isEmpty();

        verify(warnings, never()).setAiLineIfPending(any(), anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void tooLongOrBlankReplyIsDropped() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);
        when(client.write(any())).thenReturn("x".repeat(401), "   ");

        assertThat(service.addLine(w.getId())).isEmpty();
        assertThat(service.addLine(w.getId())).isEmpty();

        verify(warnings, never()).setAiLineIfPending(any(), anyString(), any());
    }

    @Test
    void unknownWarningIsIgnored() {
        UUID id = UUID.randomUUID();
        when(warnings.findById(id)).thenReturn(Optional.empty());

        assertThat(service.addLine(id)).isEmpty();
        verify(client, never()).write(any());
    }

    @Test
    void doesNothingWhenDisabledOrNotConfigured() {
        Warning w = warning(Audience.VULNERABLE, WarningStatus.PENDING_REVIEW);

        new AiLineService(client, new AiLineProperties(false, 400), warnings, events, explanations, audit, tx)
                .onWarningsDrafted(new WarningsDrafted(List.of(w.getId())));
        when(client.isConfigured()).thenReturn(false);
        service.onWarningsDrafted(new WarningsDrafted(List.of(w.getId())));

        verify(client, never()).write(any());
    }

    @Test
    void cleanCollapsesWhitespaceAndStripsQuotes() {
        assertThat(AiLineService.clean("\"Stay   indoors\ntoday.\"", 300)).isEqualTo("Stay indoors today.");
        assertThat(AiLineService.clean(null, 300)).isNull();
        assertThat(AiLineService.clean("\"\"", 300)).isNull();
        assertThat(AiLineService.clean("abcd", 3)).isNull();
    }
}
