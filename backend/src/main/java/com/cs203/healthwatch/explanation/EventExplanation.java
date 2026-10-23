package com.cs203.healthwatch.explanation;

import com.cs203.healthwatch.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The AI explanation stored for one detected event (CG-135). See migration V9. */
@Entity
@Table(name = "event_explanations")
@Getter @Setter @NoArgsConstructor
public class EventExplanation extends BaseEntity {

    // Long text fields: Postgres columns are "text"; the length only matters for the in-memory test database.
    private static final int LONG_TEXT = 10_000;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExplanationStatus status;

    @Enumerated(EnumType.STRING)
    private Assessment assessment;

    @Column(name = "likely_cause", length = LONG_TEXT)
    private String likelyCause;

    @Column(length = LONG_TEXT)
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> assumptions;

    @Column(name = "data_complete", nullable = false)
    private boolean dataComplete;

    @Column(name = "data_quality_note", nullable = false, length = LONG_TEXT)
    private String dataQualityNote;

    private String model;

    @Column(length = LONG_TEXT)
    private String error;
}
