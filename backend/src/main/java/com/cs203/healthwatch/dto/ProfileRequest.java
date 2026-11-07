package com.cs203.healthwatch.dto;

import java.util.List;

/** Full replacement of the caller's profile. Codes are checked in ProfileService; unknown codes give 400. */
public record ProfileRequest(String region, String audience, List<String> healthConditions) {
}
