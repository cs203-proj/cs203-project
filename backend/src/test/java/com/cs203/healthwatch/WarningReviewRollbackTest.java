package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.service.AuditService;
import com.cs203.healthwatch.service.WarningReviewService;
import com.cs203.healthwatch.warnings.TestWarnings;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CG-28 acceptance criterion: "Each action writes its audit entry through CG-36 in the same transaction. If the
 * audit write fails, the action fails." A real database is needed to prove the rollback actually happens, so the
 * audit service is mocked to fail and the warning is read back afterwards.
 */
@SpringBootTest
class WarningReviewRollbackTest {

    @Autowired WarningReviewService warningReviewService;
    @Autowired WarningRepository warningRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @MockBean AuditService auditService;

    private Warning pendingWarning() {
        DetectedEvent event = eventRepository.save(
                TestEvents.event(4.2, EventStatus.CONFIRMED, "2026-11-09T03:00:00Z"));
        return warningRepository.save(
                TestWarnings.warning(event.getId(), WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW));
    }

    private UUID admin() {
        return userRepository.save(new User("review-admin-" + UUID.randomUUID(), "hash", "admin")).getId();
    }

    @Test
    void failedAuditWriteRollsBackTheApproval() {
        Warning warning = pendingWarning();
        UUID actorId = admin();
        doThrow(new DataIntegrityViolationException("simulated audit write failure"))
                .when(auditService).recordByUser(any(UUID.class), any(UUID.class), anyString(), any(), any());

        assertThatThrownBy(() -> warningReviewService.approve(warning.getId(), actorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Warning reloaded = warningRepository.findById(warning.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WarningStatus.PENDING_REVIEW);
        assertThat(reloaded.getApprovedAt()).isNull();
    }

    @Test
    void failedAuditWriteRollsBackTheEdit() {
        Warning warning = pendingWarning();
        UUID actorId = admin();
        doThrow(new DataIntegrityViolationException("simulated audit write failure"))
                .when(auditService).recordByUser(any(UUID.class), any(UUID.class), anyString(), any(), any());

        assertThatThrownBy(() -> warningReviewService.edit(warning.getId(), "Edited wording.", actorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(warningRepository.findById(warning.getId()).orElseThrow().getEditedText()).isNull();
    }

    @Test
    void failedAuditWriteRollsBackTheSuppression() {
        Warning warning = pendingWarning();
        UUID actorId = admin();
        doThrow(new DataIntegrityViolationException("simulated audit write failure"))
                .when(auditService).recordByUser(any(UUID.class), any(UUID.class), anyString(), any(), any());

        assertThatThrownBy(() -> warningReviewService.suppress(warning.getId(), "Duplicate.", actorId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(warningRepository.findById(warning.getId()).orElseThrow().getStatus())
                .isEqualTo(WarningStatus.PENDING_REVIEW);
    }
}
