package com.cs203.healthwatch.dto;

import com.cs203.healthwatch.model.User;
import java.util.List;

public record ProfileResponse(String region, String audience, List<String> healthConditions) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getRegion(), user.getAudience(),
                user.getHealthConditions().stream().sorted().toList());
    }
}
