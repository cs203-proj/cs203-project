package com.cs203.healthwatch.warnings;

import java.util.List;
import java.util.UUID;

/** Published when warning drafts are saved. {@link AiLineService} picks it up once the transaction has committed. */
public record WarningsDrafted(List<UUID> warningIds) {
}
