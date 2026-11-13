package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A warning drafted from an investigated event.
 */
@Entity
@Table(name = "warnings")
@Getter @Setter @NoArgsConstructor
public class Warning extends BaseEntity {

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String audience = "GENERAL_PUBLIC";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WarningSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WarningStatus status = WarningStatus.PENDING_REVIEW;

    /** The AI's original draft. Set once by CG-21 and never overwritten. */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String warningText;

    /** When the warning was approved (CG-28). Null until then; CG-22 treats it as "active" for a configurable window. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The admin's edited wording (CG-28). Null if the admin did not edit; {@link #warningText} is never changed. */
    @Column(name = "edited_text", columnDefinition = "text")
    private String editedText;

    /** Optional AI-written personalised sentence (CG-154). End users see it labelled as AI-generated. */
    @Column(name = "ai_line", columnDefinition = "text")
    private String aiLine;

    /** What an end user should read: the admin's edit if there is one, otherwise the original draft. */
    public String displayText() {
        return (editedText != null && !editedText.isBlank()) ? editedText : warningText;
    }
}
