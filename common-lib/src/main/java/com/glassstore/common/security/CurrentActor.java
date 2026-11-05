package com.glassstore.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;

/** Trusted identity from the validated security context; never from request headers/body IDs. */
public final class CurrentActor {
    private CurrentActor() {}
    public static Authentication authentication() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in");
        return auth;
    }
    public static boolean hasRole(String role) {
        return authentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    public static boolean customer() {
        return hasRole("CUSTOMER") && !hasRole("ADMIN") && !hasRole("SALES_STAFF")
                && !hasRole("OPTOMETRIST") && !hasRole("LAB_TECH");
    }
    public static Long id() {
        var auth = authentication();
        Object value = auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaim("userId") : null;
        try { return Long.valueOf(value == null ? auth.getName() : value.toString()); }
        catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User identity is missing"); }
    }
    public static String name() {
        var auth = authentication();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("username");
            if (username != null && !username.isBlank()) return username;
        }
        return auth.getName();
    }
    public static void requireOwner(Long customerId) {
        if (customer() && !Objects.equals(customerId, id()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot access another customer's data");
    }
}
