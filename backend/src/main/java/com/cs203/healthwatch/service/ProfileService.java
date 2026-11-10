package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ForbiddenException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.ProfileRequest;
import com.cs203.healthwatch.dto.ProfileResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.profile.Audience;
import com.cs203.healthwatch.profile.HealthCondition;
import com.cs203.healthwatch.profile.Region;
import com.cs203.healthwatch.repository.UserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CG-6 (view/edit own profile) and CG-7 (anonymise own account). Every method takes the id from the token. */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final Clock clock;

    public ProfileService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
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

    /**
     * Anonymises the account instead of deleting the row: audit_log is append-only and references users as actors,
     * so the id must survive. Personal data is wiped and the old username is replaced by a random one.
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        User user = activeUser(userId);
        if ("admin".equalsIgnoreCase(user.getRole())) {
            throw new ForbiddenException("Admin accounts cannot be deleted through this endpoint");
        }
        user.setUsername("deleted-" + UUID.randomUUID());
        user.setPasswordHash(null);
        user.setRegion(null);
        user.setAudience(null);
        user.getHealthConditions().clear();
        // no consent columns exist yet; null them here when they are added
        user.setDeletedAt(clock.instant());
        userRepository.save(user);
    }

    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
