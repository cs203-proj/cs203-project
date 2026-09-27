package com.cs203.healthwatch.ingestion.readings;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "readings", uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "region", "observed_at"}))
public class Reading {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id")
    private UUID sourceId;

    private String region;
    
    @Column(name = "value")
    private Double value;

    @Column(name = "observed_at")
    private OffsetDateTime observedAt;

    @Column(name = "ingested_at")
    private OffsetDateTime ingestedAt;

    @Column(name = "is_malformed")
    private boolean malformed;
    
    @Column(name = "is_synthetic")
    private boolean synthetic;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;
    
}