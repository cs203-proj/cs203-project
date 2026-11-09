package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.ProfileRequest;
import com.cs203.healthwatch.dto.ProfileResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.profile.HealthCondition;
import com.cs203.healthwatch.profile.Region;
import com.cs203.healthwatch.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CG-6: view and edit the caller's own profile. Every method takes the id from the token. */
@Service
public class ProfileService {

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        return ProfileResponse.from(activeUser(userId));
    }

    /** Full replace. Unknown codes give 400 naming the field and the rejected value. */
    @Transactional
    public ProfileResponse update(UUID userId, ProfileRequest request) {
        List<String> problems = new ArrayList<>();
        if (request.region() == null || Region.fromCode(request.region()).isEmpty()) {
            problems.add("region: unknown value '" + request.region() + "', allowed: " + Region.codes());
        }
        if (request.audience() == null || Audience.fromCode(request.audience()).isEmpty()) {
            problems.add("audience: unknown value '" + request.audience() + "', allowed: " + Audience.codes());
        }
        Set<String> conditions = new LinkedHashSet<>();
        for (String code : request.healthConditions() == null ? List.<String>of() : request.healthConditions()) {
            if (code == null || HealthCondition.fromCode(code).isEmpty()) {
                problems.add("healthConditions: unknown value '" + code + "', allowed: " + HealthCondition.codes());
            } else {
                conditions.add(code);
            }
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }

        User user = activeUser(userId);
        user.setRegion(request.region());
        user.setAudience(request.audience());
        user.getHealthConditions().clear();
        user.getHealthConditions().addAll(conditions);
        return ProfileResponse.from(userRepository.save(user));
    }

    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
