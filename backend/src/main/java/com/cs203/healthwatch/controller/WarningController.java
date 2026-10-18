package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.dto.WarningAuditEntryResponse;
import com.cs203.healthwatch.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/warnings")
@Tag(name = "Warnings", description = "Drafted warnings and their audit trail (admin only)")
public class WarningController {

    private final AuditService auditService;

    public WarningController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/{id}/audit")
    @Operation(
            summary = "Get a warning's audit trail",
            description = "Every action on the warning, oldest first, with who did it, when, and the "
                    + "before/after state. Entries are append-only. Returns an empty list if there are none.")
    public List<WarningAuditEntryResponse> auditTrail(
            @Parameter(description = "Warning id") @PathVariable UUID id) {
        return auditService.trail(id);
    }
}