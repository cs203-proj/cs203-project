package com.cs203.healthwatch.ingestion.sources;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {
    Optional<DataSource> findByName(String name);
}
