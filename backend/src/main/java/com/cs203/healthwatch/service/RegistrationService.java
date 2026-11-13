package com.cs203.healthwatch.service;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.dto.RegisterRequest;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-user sign-up (CG-5) with consent (CG-8).
 *
 * <p>Two rules this class exists to enforce:
 *
 * <ul>
 *   <li>Registration can only ever create an end user. The role is set here, not taken from the request, so a
 *       public endpoint can never mint an account that reaches the admin routes.
 *   <li>No consent, no account. Consent is checked before the user is built, so a rejected registration leaves
 *       nothing behind.
 * </ul>
 */
@Service
public class RegistrationService {

    /**
     * The role every registered account gets. "viewer" is the non-admin role already used throughout the codebase
     * and its tests; admin-only routes require ROLE_ADMIN, so a viewer token is rejected with 403 there.
     */
    public static final String END_USER_ROLE = "viewer";

    /** The most bcrypt can use; see register(). */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterRequest request) {
        requireConsent(request);

        // bcrypt only reads the first 72 BYTES, so a longer password would be silently cut short and two different
        // long passwords could hash the same. @Size caps characters; this catches multi-byte characters (e.g.
        // emoji) that fit in 72 characters but not in 72 bytes.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("Password is too long: it must be at most " + MAX_PASSWORD_BYTES + " bytes.");
        }

        String username = request.username().trim();
        // @Size looks at the text as sent, so "  a  " passes it; the stored name is the trimmed one, so check that.
        if (username.length() < 3) {
            throw new BadRequestException("username must be at least 3 characters, not counting spaces");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("Username '" + username + "' is already taken");
        }

        User user = new User(username, passwordEncoder.encode(request.password()), END_USER_ROLE);
        user.setConsentVersion(ConsentTerms.VERSION);
        user.setConsentedAt(Instant.now());

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two registrations for the same username can pass the check above at the same time; the unique
            // constraint on users.username is what actually decides, so the loser gets the same 409.
            throw new ConflictException("Username '" + username + "' is already taken");
        }
    }

    /**
     * The consent box must be ticked, and the client must quote the version of the notice it displayed. Quoting an
     * old version means the user agreed to wording we have since changed, so they are asked again rather than
     * silently recorded as having accepted the current text.
     */
    private static void requireConsent(RegisterRequest request) {
        if (!Boolean.TRUE.equals(request.consent())) {
            throw new BadRequestException(
                    "Consent is required to create an account. See GET /consent for what you are agreeing to.");
        }
        if (!ConsentTerms.VERSION.equals(request.consentVersion())) {
            throw new BadRequestException("Consent version '" + request.consentVersion()
                    + "' is not the current one. The current version is '" + ConsentTerms.VERSION
                    + "' — fetch GET /consent and ask the user to accept it again.");
        }
    }
}
