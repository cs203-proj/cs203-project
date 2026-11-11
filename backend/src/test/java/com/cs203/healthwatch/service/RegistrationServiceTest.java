package com.cs203.healthwatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.dto.RegisterRequest;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Sign-up rules for CG-5 and CG-8, with the repository and password encoder mocked. */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(userRepository, passwordEncoder);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static RegisterRequest valid() {
        return new RegisterRequest("alex.tan", "correct-horse", true, ConsentTerms.VERSION);
    }

    private void noExistingUser() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    // --- the happy path -----------------------------------------------------------------------------------------

    @Test
    void registersAnEndUserWithAHashedPassword() {
        noExistingUser();

        User created = service.register(valid());

        assertThat(created.getUsername()).isEqualTo("alex.tan");
        assertThat(created.getPasswordHash()).isEqualTo("bcrypt-hash");
        verify(passwordEncoder).encode("correct-horse");
    }

    @Test
    void neverStoresThePasswordInPlainText() {
        noExistingUser();

        User created = service.register(valid());

        assertThat(created.getPasswordHash()).isNotEqualTo("correct-horse");
    }

    @Test
    void recordsTheConsentVersionAndTime() {
        noExistingUser();

        User created = service.register(valid());

        assertThat(created.getConsentVersion()).isEqualTo(ConsentTerms.VERSION);
        assertThat(created.getConsentedAt()).isNotNull();
    }

    @Test
    void trimsWhitespaceAroundTheUsername() {
        noExistingUser();

        User created = service.register(
                new RegisterRequest("  alex.tan  ", "correct-horse", true, ConsentTerms.VERSION));

        assertThat(created.getUsername()).isEqualTo("alex.tan");
    }

    // --- registration can never create an admin -----------------------------------------------------------------

    @Test
    void alwaysCreatesTheEndUserRole() {
        noExistingUser();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);

        service.register(valid());

        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(RegistrationService.END_USER_ROLE);
        assertThat(saved.getValue().getRole()).isNotEqualTo("admin");
    }

    // --- consent (CG-8) -----------------------------------------------------------------------------------------

    @Test
    void withoutConsentNoAccountIsCreated() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alex.tan", "correct-horse", false, ConsentTerms.VERSION)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Consent is required");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void missingConsentFieldIsTreatedAsNoConsent() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alex.tan", "correct-horse", null, ConsentTerms.VERSION)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Consent is required");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void anOutOfDateConsentVersionIsRejected() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alex.tan", "correct-horse", true, "0.9")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not the current one");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    // --- duplicates ---------------------------------------------------------------------------------------------

    @Test
    void aTakenUsernameIs409() {
        when(userRepository.findByUsername("alex.tan"))
                .thenReturn(Optional.of(new User("alex.tan", "hash", "viewer")));

        assertThatThrownBy(() -> service.register(valid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void aUsernameTakenBetweenTheCheckAndTheSaveIsAlso409() {
        noExistingUser();
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> service.register(valid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void passwordOver72Bytes_isRejectedEvenWhenUnder72Characters() {
        // 30 emoji = 30 characters (60 UTF-16 units) but 120 UTF-8 bytes, more than bcrypt can use
        String emoji = "\uD83D\uDE00".repeat(30);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("alex.tan", emoji, true, ConsentTerms.VERSION)))
                .isInstanceOf(BadRequestException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void usernameThatIsTooShortOnceTrimmed_isRejected() {
        assertThatThrownBy(() -> service.register(
                new RegisterRequest("  a  ", "correct-horse", true, ConsentTerms.VERSION)))
                .isInstanceOf(BadRequestException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registerRequestToStringHidesThePassword() {
        assertThat(valid().toString()).doesNotContain("correct-horse");
    }
}
