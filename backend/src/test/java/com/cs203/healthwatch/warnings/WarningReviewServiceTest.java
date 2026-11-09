package com.cs203.healthwatch.warnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.service.AuditService;
import com.cs203.healthwatch.service.WarningReviewService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Review rules for CG-28, with the repository and audit log mocked. The transaction guarantee itself (a failed
 * audit write rolls the change back) needs a real database, so it lives in WarningReviewRollbackTest.
 */
@ExtendWith(MockitoExtension.class)
class WarningReviewServiceTest {

    @Mock WarningRepository warningRepository;
    @Mock AuditService auditService;
    @InjectMocks WarningReviewService service;

    private UUID warningId;
    private UUID actorId;

    @BeforeEach
    void ids() {
        warningId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private Warning pending(WarningSeverity severity) {
        Warning warning = TestWarnings.warning(UUID.randomUUID(), severity, WarningStatus.PENDING_REVIEW);
        warning.setId(warningId);
        when(warningRepository.findByIdForUpdate(warningId)).thenReturn(Optional.of(warning));
        // lenient: the tests that expect a rejection never reach the save, and strict stubbing would fail them.
        lenient().when(warningRepository.saveAndFlush(any(Warning.class)))
                .thenAnswer(call -> call.getArgument(0));
        return warning;
    }

    // --- approve ------------------------------------------------------------------------------------------------

    @Test
    void approve_setsStatusAndStampsApprovalTime() {
        Warning warning = pending(WarningSeverity.HIGH);

        WarningResponse response = service.approve(warningId, actorId);

        assertThat(warning.getStatus()).isEqualTo(WarningStatus.APPROVED);
        assertThat(warning.getApprovedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(WarningStatus.APPROVED);
        verify(auditService).recordByUser(eq(warningId), eq(actorId), eq(WarningReviewService.APPROVED), any(), any());
    }

    @Test
    void approve_recordsTheStatusBeforeAndAfter() {
        pending(WarningSeverity.HIGH);
        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);

        service.approve(warningId, actorId);

        verify(auditService).recordByUser(any(), any(), anyString(), before.capture(), after.capture());
        assertThat(asMap(before.getValue())).containsEntry("status", WarningStatus.PENDING_REVIEW);
        assertThat(asMap(after.getValue())).containsEntry("status", WarningStatus.APPROVED);
    }

    // --- edit ---------------------------------------------------------------------------------------------------

    @Test
    void edit_keepsTheOriginalTextAndStaysPendingReview() {
        Warning warning = pending(WarningSeverity.HIGH);
        String original = warning.getWarningText();

        WarningResponse response = service.edit(warningId, "Air quality is poor. Stay indoors.", actorId);

        assertThat(warning.getWarningText()).isEqualTo(original);
        assertThat(warning.getEditedText()).isEqualTo("Air quality is poor. Stay indoors.");
        assertThat(warning.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
        assertThat(response.warningText()).isEqualTo(original);
        assertThat(response.editedText()).isEqualTo("Air quality is poor. Stay indoors.");
        verify(auditService).recordByUser(eq(warningId), eq(actorId), eq(WarningReviewService.EDITED), any(), any());
    }

    @Test
    void edit_isWhatEndUsersWouldSee() {
        Warning warning = pending(WarningSeverity.HIGH);

        service.edit(warningId, "Air quality is poor. Stay indoors.", actorId);

        assertThat(warning.displayText()).isEqualTo("Air quality is poor. Stay indoors.");
    }

    // --- downgrade ----------------------------------------------------------------------------------------------

    @Test
    void downgrade_lowersSeverityAndRecordsTheReason() {
        Warning warning = pending(WarningSeverity.HIGH);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);

        service.downgrade(warningId, "MEDIUM", "Readings fell back towards the baseline.", actorId);

        assertThat(warning.getSeverity()).isEqualTo(WarningSeverity.MEDIUM);
        assertThat(warning.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
        verify(auditService).recordByUser(any(), any(), eq(WarningReviewService.DOWNGRADED), any(), after.capture());
        assertThat(asMap(after.getValue()))
                .containsEntry("reason", "Readings fell back towards the baseline.")
                .containsEntry("severity", WarningSeverity.MEDIUM);
    }

    @Test
    void downgrade_acceptsLowercaseSeverity() {
        Warning warning = pending(WarningSeverity.CRITICAL);

        service.downgrade(warningId, "low", "Episode has passed.", actorId);

        assertThat(warning.getSeverity()).isEqualTo(WarningSeverity.LOW);
    }

    @Test
    void downgrade_toTheSameSeverityIsRejected() {
        pending(WarningSeverity.HIGH);

        assertThatThrownBy(() -> service.downgrade(warningId, "HIGH", "No change.", actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("less severe");

        verifyNoInteractions(auditService);
    }

    @Test
    void downgrade_toAHigherSeverityIsRejected() {
        Warning warning = pending(WarningSeverity.MEDIUM);

        assertThatThrownBy(() -> service.downgrade(warningId, "CRITICAL", "Escalating.", actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot downgrade from MEDIUM to CRITICAL");

        assertThat(warning.getSeverity()).isEqualTo(WarningSeverity.MEDIUM);
        verifyNoInteractions(auditService);
    }

    @Test
    void downgrade_withAnUnknownSeverityIsRejected() {
        assertThatThrownBy(() -> service.downgrade(warningId, "SEVERE", "Typo.", actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown severity 'SEVERE'");

        verifyNoInteractions(auditService);
    }

    // --- suppress -----------------------------------------------------------------------------------------------

    @Test
    void suppress_setsStatusAndRecordsTheReason() {
        Warning warning = pending(WarningSeverity.LOW);
        ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);

        service.suppress(warningId, "Duplicate of an approved warning.", actorId);

        assertThat(warning.getStatus()).isEqualTo(WarningStatus.SUPPRESSED);
        assertThat(warning.getApprovedAt()).isNull();
        verify(auditService).recordByUser(any(), any(), eq(WarningReviewService.SUPPRESSED), any(), after.capture());
        assertThat(asMap(after.getValue())).containsEntry("reason", "Duplicate of an approved warning.");
    }

    // --- guards shared by every action --------------------------------------------------------------------------

    @Test
    void anActionOnAnApprovedWarningIsRejected() {
        Warning warning = TestWarnings.warning(UUID.randomUUID(), WarningSeverity.HIGH, WarningStatus.APPROVED);
        warning.setId(warningId);
        when(warningRepository.findByIdForUpdate(warningId)).thenReturn(Optional.of(warning));

        assertThatThrownBy(() -> service.approve(warningId, actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only a PENDING_REVIEW warning");

        verifyNoInteractions(auditService);
    }

    @Test
    void aSuppressedWarningCannotBeApprovedAfterwards() {
        Warning warning = TestWarnings.warning(UUID.randomUUID(), WarningSeverity.HIGH, WarningStatus.SUPPRESSED);
        warning.setId(warningId);
        when(warningRepository.findByIdForUpdate(warningId)).thenReturn(Optional.of(warning));

        assertThatThrownBy(() -> service.approve(warningId, actorId))
                .isInstanceOf(BadRequestException.class);

        assertThat(warning.getStatus()).isEqualTo(WarningStatus.SUPPRESSED);
        verifyNoInteractions(auditService);
    }

    @Test
    void anUnknownWarningIs404() {
        when(warningRepository.findByIdForUpdate(warningId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(warningId, actorId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(warningId.toString());

        verifyNoInteractions(auditService);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object snapshot) {
        return (Map<String, Object>) snapshot;
    }
}
