package com.cs203.healthwatch.warnings;

/**
* How serious a warning is, declared from least to most severe. The review queue (CG-29) ranks by this order,
* so add new values in their correct position, not at the end.
*/
public enum WarningSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}
