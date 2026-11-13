package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.RegisterRequest;
import com.cs203.healthwatch.dto.RegisterResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.service.RegistrationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Register, sign in and sign out")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RegistrationService registrationService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                          RegistrationService registrationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Register an end-user account",
            description = "Public. Creates an end-user account and records the consent the user gave (CG-8). "
                    + "Registration can never create an admin: there is no role field, and the role is set by the "
                    + "server. The password is stored only as a bcrypt hash. No token is returned — sign in "
                    + "afterwards with POST /auth/login. Profile details (region, audience, health conditions) are "
                    + "set separately through the profile endpoints.")
    @ApiResponse(responseCode = "201", description = "Account created",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RegisterResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "id": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                              "username": "alex.tan",
                              "role": "viewer",
                              "consentVersion": "1.0",
                              "consentedAt": "2026-11-10T08:11:00Z"
                            }
                            """)))
    @ApiResponse(responseCode = "400",
            description = "Missing or too-short password, missing username, consent not given, or an out-of-date "
                    + "consent version. No account is created.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(name = "Consent not given", value = """
                            {
                              "timestamp": "2026-11-10T08:10:00Z",
                              "status": 400,
                              "error": "Bad Request",
                              "message": "Consent is required to create an account. See GET /consent for what you are agreeing to.",
                              "details": []
                            }
                            """)))
    @ApiResponse(responseCode = "409", description = "That username is already taken",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ApiErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "timestamp": "2026-11-10T08:12:00Z",
                              "status": 409,
                              "error": "Conflict",
                              "message": "Username 'alex.tan' is already taken",
                              "details": []
                            }
                            """)))
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return RegisterResponse.from(registrationService.register(request));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Sign in",
            description = "Public. Returns a signed JWT carrying the user's id and role. Admins get an admin token; "
                    + "registered end users get an end-user token, which is rejected with 403 on admin-only "
                    + "endpoints. A wrong username, a wrong password and a deleted account all give the same 401, "
                    + "so the response cannot be used to find out which usernames exist. Send the token as "
                    + "'Authorization: Bearer <token>'.")
    @ApiResponse(responseCode = "200", description = "Signed in",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            { "token": "eyJhbGciOiJIUzI1NiJ9..." }
                            """)))
    @ApiResponse(responseCode = "401", description = "Unknown username, wrong password, or a deleted account",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            { "error": "Invalid username or password" }
                            """)))
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        Optional<User> userOpt = userRepository.findByUsername(req.username());

        // generic failure for both "no such user" and "wrong password"
        if (userOpt.isEmpty() || userOpt.get().isDeleted() || userOpt.get().getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), userOpt.get().getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        User user = userOpt.get();
        String token = jwtService.generateToken(user.getId(), user.getUsername(), user.getRole());

        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Sign out",
            description = "Tokens are stateless, so there is no server-side session to end and this endpoint does "
                    + "nothing on the server. Signing out means the client discards its token; after that it can no "
                    + "longer authenticate. A token that has already been issued stays valid until it expires, "
                    + "30 minutes after it was issued, which is why the lifetime is kept short. It exists so "
                    + "clients have one obvious thing to call, and so this behaviour is written down rather than "
                    + "assumed. Revoking a token before it expires would need a deny-list, which is not in scope.")
    @ApiResponse(responseCode = "204", description = "Acknowledged; discard the token on the client")
    public void logout() {
        // Intentionally empty: see the description above.
    }

    public record LoginRequest(String username, String password) {}
}
