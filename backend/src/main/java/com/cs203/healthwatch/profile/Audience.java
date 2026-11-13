package com.cs203.healthwatch.profile;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Warning audiences, uppercase on the wire. GENERAL_PUBLIC matches warnings.audience (CG-21); shared with CG-154 and CG-22. */
public enum Audience {
    GENERAL_PUBLIC("GENERAL_PUBLIC"), VULNERABLE("VULNERABLE"), HEALTHCARE_WORKER("HEALTHCARE_WORKER");

    private final String code;

    Audience(String code) {
        this.code = code;
    }

    /** The value used in the API and the database. */
    public String code() {
        return code;
    }

    public static Optional<Audience> fromCode(String code) {
        return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Audience::code).toList();
    }
}
