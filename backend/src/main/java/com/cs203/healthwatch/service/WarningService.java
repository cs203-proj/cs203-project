package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.WarningListResponse;
import com.cs203.healthwatch.dto.WarningResponse;
import com.cs203.healthwatch.repository.OffsetPageRequest;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of generated warnings: the admin review queue (CG-29). */
@Service
public class WarningService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final WarningRepository warningRepository;

    public WarningService(WarningRepository warningRepository) {
        this.warningRepository = warningRepository;
    }

    /**
     * Warnings sorted by severity (most severe first), then by age (oldest first).
     *
     * @param status optional status filter (case-insensitive); null returns every status
     */
    @Transactional(readOnly = true)
    public WarningListResponse queue(String status, int limit, long offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be 0 or greater");
        }
        WarningStatus statusFilter = (status == null) ? null : WarningStatus.parse(status);

        OffsetPageRequest page = new OffsetPageRequest(offset, limit);
        Page<Warning> result = (statusFilter == null)
                ? warningRepository.findQueue(page)
                : warningRepository.findQueue(statusFilter, page);

        List<WarningResponse> items = result.getContent().stream().map(WarningResponse::from).toList();
        return new WarningListResponse(items, result.getTotalElements(), limit, offset);
    }
}
