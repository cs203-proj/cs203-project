package com.cs203.healthwatch.profile;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Profile regions, lowercase on the wire. Same values as pm.regions in application.yml; shared with CG-154 and CG-22. */
public enum Region {
    CENTRAL("central"), NORTH("north"), SOUTH("south"), EAST("east"), WEST("west");

    private final String code;

    Region(String code) {
        this.code = code;
    }

    /** The value used in the API and the database. */
    public String code() {
        return code;
    }

    public static Optional<Region> fromCode(String code) {
        return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Region::code).toList();
    }
}
