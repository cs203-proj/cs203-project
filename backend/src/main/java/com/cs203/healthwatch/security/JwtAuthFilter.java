package com.cs203.healthwatch.security;

import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // no token — let it fall through; SecurityConfig's authorizeHttpRequests
        // will reject it with 401 if the route actually requires auth
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7); // strip "Bearer "

        try {
            Claims claims = jwtService.validateAndParse(token);
            String role = claims.get("role", String.class);
            UUID userId = userIdFrom(claims);

            // look the account up by id, never by name: a deleted account's name can be taken by a new account
            Optional<User> userOpt = userId == null ? Optional.empty() : userRepository.findById(userId);
            // an anonymised (deleted) account must never authenticate, even with a still-valid token
            if (userOpt.isPresent() && !userOpt.get().isDeleted() && role != null) {
                User user = userOpt.get();
                var authToken = new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(user.getId(), user.getUsername()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (JwtException e) {
            // invalid/expired token — leave SecurityContext empty,
            // authorizeHttpRequests will reject with 401
        }

        filterChain.doFilter(request, response);
    }

    /** Tokens without a valid {@code uid} claim (for example ones issued before it existed) do not authenticate. */
    private static UUID userIdFrom(Claims claims) {
        String uid = claims.get("uid", String.class);
        if (uid == null) {
            return null;
        }
        try {
            return UUID.fromString(uid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
