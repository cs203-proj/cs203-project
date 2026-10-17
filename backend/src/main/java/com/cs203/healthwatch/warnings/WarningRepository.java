package com.cs203.healthwatch.warnings;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarningRepository extends JpaRepository<Warning, UUID> {
}
