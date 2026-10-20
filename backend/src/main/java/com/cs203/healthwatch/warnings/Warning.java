package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
}
