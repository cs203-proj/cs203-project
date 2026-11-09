package com.cs203.healthwatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.dto.ProfileRequest;
import com.cs203.healthwatch.dto.ProfileResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository userRepository;
    ProfileService service;
    User user;

    @BeforeEach
    void setUp() {
        service = new ProfileService(userRepository);
        user = new User("alice", "hash", "user");
        user.setId(UUID.randomUUID());
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(user)).thenReturn(user);
    }

    @Test
    void update_storesProfileAndDedupesConditions() {
        ProfileResponse response = service.update(user.getId(),
                new ProfileRequest("north", "VULNERABLE", List.of("COPD", "ASTHMA", "COPD")));

        assertThat(response.region()).isEqualTo("north");
        assertThat(response.audience()).isEqualTo("VULNERABLE");
        assertThat(response.healthConditions()).containsExactly("ASTHMA", "COPD");
    }

    @Test
    void update_emptyConditionsClearsThem() {
        service.update(user.getId(), new ProfileRequest("north", "VULNERABLE", List.of("ASTHMA")));
        ProfileResponse response = service.update(user.getId(), new ProfileRequest("south", "GENERAL_PUBLIC", List.of()));

        assertThat(response.healthConditions()).isEmpty();
    }

    @Test
    void update_unknownRegion_isRejectedAndNamesFieldAndValue() {
        assertThatThrownBy(() -> service.update(user.getId(), new ProfileRequest("mars", "VULNERABLE", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("region").hasMessageContaining("mars");
    }

    @Test
    void update_regionIsCaseSensitive() {
        assertThatThrownBy(() -> service.update(user.getId(), new ProfileRequest("North", "VULNERABLE", List.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_unknownAudience_isRejected() {
        assertThatThrownBy(() -> service.update(user.getId(), new ProfileRequest("north", "VIP", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("audience").hasMessageContaining("VIP");
    }

    @Test
    void update_unknownCondition_isRejected() {
        assertThatThrownBy(() -> service.update(user.getId(),
                new ProfileRequest("north", "VULNERABLE", List.of("ASTHMA", "my knee hurts"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("healthConditions").hasMessageContaining("my knee hurts");
    }

    @Test
    void update_rejectedRequestLeavesProfileUntouched() {
        user.setRegion("east");
        assertThatThrownBy(() -> service.update(user.getId(), new ProfileRequest("mars", "VULNERABLE", List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThat(user.getRegion()).isEqualTo("east");
    }
}
