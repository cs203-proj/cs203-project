package com.cs203.healthwatch.security;

import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    void noToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/events/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_returnsUnauthorized() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        // build a token that already expired 1 minute ago, using the same secret
        String expiredToken = Jwts.builder()
                .subject("admin")
                .claim("role", "admin")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/events/ping")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminRole_returnsForbidden() throws Exception {
        User user = new User("regularuser", "hashed-password", "user");
        when(userRepository.findByUsername("regularuser")).thenReturn(Optional.of(user));

        String token = jwtService.generateToken("regularuser", "user");

        mockMvc.perform(get("/events/ping")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}