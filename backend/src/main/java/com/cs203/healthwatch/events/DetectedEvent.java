package com.cs203.healthwatch.events;

import com.cs203.healthwatch.common.BaseEntity;
import com.cs203.healthwatch.confidence.ConfidenceInputs;
import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.confidence.ConfidenceResult;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "events")
@Getter @Setter @NoArgsConstructor
public class DetectedEvent extends BaseEntity {

    @Column(nullable = false)
    private String signalType;

    @Column(nullable = false)
    private String region;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    private Double deviation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    // Named "replay" (not "isReplay") so Lombok generates isReplay()/setReplay()
    // and Spring Data queries can refer to it as "Replay"
    @Column(name = "is_replay", nullable = false)
    private boolean replay = false;

    // Confidence (CG-16): inputs + score + formula version, all computed by ConfidenceScorer. Null on events that
    // pre-date V5. The deviation input is the `deviation` column above.
    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level")
    private ConfidenceLevel confidenceLevel;

    @Column(name = "confidence_formula_version")
    private Integer confidenceFormulaVersion;

    @Column(name = "confidence_staleness_seconds")
    private Long confidenceStalenessSeconds;

    @Column(name = "confidence_reading_count")
    private Integer confidenceReadingCount;

    @Column(name = "confidence_source_agreement")
    private Double confidenceSourceAgreement;

    /** Stores the inputs and the score computed from them, together. */
    public void applyConfidence(ConfidenceInputs inputs, ConfidenceResult result) {
        this.confidenceStalenessSeconds = inputs.stalenessSeconds();
        this.confidenceReadingCount = inputs.readingCount();
        this.confidenceSourceAgreement = inputs.sourceAgreement();
        this.confidenceScore = result.score();
        this.confidenceLevel = result.level();
        this.confidenceFormulaVersion = result.formulaVersion();
    }
}
