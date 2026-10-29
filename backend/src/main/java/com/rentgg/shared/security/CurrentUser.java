package com.rentgg.shared.security;

import com.rentgg.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Access to the authenticated caller: JWT subject, roles and permissions (resolved from the database). */
public final class CurrentUser {

    private CurrentUser() {}

    public static UUID id() {
        return optionalId().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Bạn cần đăng nhập."));
    }

    public static Optional<UUID> optionalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(UUID.fromString(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }

    /** Role codes (for display and audit; authorization should use {@link #has(Permission)}). */
    public static Set<String> roles() {
        return authorities().stream()
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toSet());
    }

    /** Permission codes such as {@code listing:approve}. */
    public static Set<String> permissions() {
        return authorities().stream()
                .filter(a -> !a.startsWith("ROLE_") && a.indexOf(':') > 0)
                .collect(Collectors.toSet());
    }

    public static boolean has(Permission permission) {
        return authorities().contains(permission.code());
    }

    public static boolean hasAny(Permission... permissions) {
        Set<String> mine = authorities();
        for (Permission p : permissions) {
            if (mine.contains(p.code())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> authorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
