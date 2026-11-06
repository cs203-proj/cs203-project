package com.cs203.healthwatch.warnings;

/**
 * How serious a warning is, declared from least to most severe. The review queue (CG-29) does not use this
 * declaration order: it ranks with the CASE in {@link WarningRepository#QUEUE_ORDER}, so a new value must also get
 * its own WHEN there (and in the chk_warnings_severity constraint, via a new migration).
 */
public enum WarningSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}
