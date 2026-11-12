package com.cs203.healthwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.dto.RegisterRequest;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.service.RegistrationService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * CG-5 / CG-8 verification against the real database set up by the Flyway migrations (no mocks). It proves the V15
 * migration really adds the consent columns, that a registered user is stored with the end-user role and a bcrypt
 * hash (never the plain password), and that the unique constraint on username turns a repeat into a 409.
 */
@SpringBootTest
class RegistrationIntegrationTest {

    @Autowired RegistrationService registrationService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final String username = "reg-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(username).ifPresent(userRepository::delete);
    }

    private RegisterRequest request() {
        return new RegisterRequest(username, "correct-horse-battery", true, ConsentTerms.VERSION);
    }

    @Test
    void registeredUserIsStoredWithRoleHashAndConsent() {
        registrationService.register(request());

        User stored = userRepository.findByUsername(username).orElseThrow();
        assertThat(stored.getRole()).isEqualTo("viewer");
        assertThat(stored.getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(passwordEncoder.matches("correct-horse-battery", stored.getPasswordHash())).isTrue();
        assertThat(stored.getConsentVersion()).isEqualTo(ConsentTerms.VERSION);
        assertThat(stored.getConsentedAt()).isNotNull();
    }

    @Test
    void registeringTheSameUsernameTwiceIsAConflict() {
        registrationService.register(request());

        assertThatThrownBy(() -> registrationService.register(request()))
                .isInstanceOf(ConflictException.class);
    }
}
