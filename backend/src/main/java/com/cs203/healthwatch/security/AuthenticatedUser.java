package com.cs203.healthwatch.security;

import java.security.Principal;
import java.util.UUID;

/**
 * The principal JwtAuthFilter puts in the SecurityContext. Controllers read it with
 * {@code @AuthenticationPrincipal AuthenticatedUser user} to learn who made the request, e.g. the actor recorded
 * in the audit log. It always comes from a verified token, never from the request body.
 */
public record AuthenticatedUser(UUID id, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
