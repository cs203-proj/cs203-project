package com.cs203.healthwatch.ingestion.sources;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "data_sources", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class DataSource {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", nullable = false)
    private String type;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    // Disabled sources are skipped by ingestion and backfill
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // Feed URL. Null means ingestion uses the configured url (pm.url).
    @Column(name = "url")
    private String url;

    public DataSource(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
