package com.cs203.healthwatch.profile;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Fixed list of health condition codes (no free-text medical data). */
public enum HealthCondition {
    ASTHMA("ASTHMA"), COPD("COPD"), HEART_DISEASE("HEART_DISEASE"), DIABETES("DIABETES"), PREGNANCY("PREGNANCY");

    private final String code;

    HealthCondition(String code) {
        this.code = code;
    }

    /** The value used in the API and the database. */
    public String code() {
        return code;
    }

    public static Optional<HealthCondition> fromCode(String code) {
        return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(HealthCondition::code).toList();
    }
}
