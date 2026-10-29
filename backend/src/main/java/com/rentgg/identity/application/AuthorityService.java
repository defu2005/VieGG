package com.rentgg.identity.application;

import com.rentgg.shared.security.AuthorityResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a user's roles and permissions from the database, with a short in-memory cache. Changes made through
 * {@link RbacService} invalidate the cache immediately on this instance; other instances pick them up within
 * {@link #TTL}.
 */
@Service
public class AuthorityService implements AuthorityResolver {

    static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 50_000;

    private final JdbcTemplate jdbc;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public AuthorityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Authorities resolve(UUID userId) {
        long now = System.nanoTime();
        Cached c = cache.get(userId);
        if (c != null && now - c.loadedAt < TTL.toNanos()) {
            return c.value;
        }
        Authorities fresh = load(userId);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, new Cached(fresh, now));
        return fresh;
    }

    public Set<String> permissionsOf(UUID userId) {
        return resolve(userId).permissions();
    }

    /** Bypasses the cache; for security decisions that must see the latest state (anti-escalation checks). */
    public Authorities fresh(UUID userId) {
        return load(userId);
    }

    public void invalidate(UUID userId) {
        cache.remove(userId);
    }

    public void invalidateAll() {
        cache.clear();
    }

    /**
     * A staff account (holding any staff role) gets only the permissions of its staff roles: the customer role
     * USER stays assigned but grants nothing, so staff cannot rent, list accounts or withdraw with that login.
     */
    private Authorities load(UUID userId) {
        Set<String> roles = new HashSet<>();
        Set<String> staffPermissions = new HashSet<>();
        Set<String> customerPermissions = new HashSet<>();
        boolean[] staff = {false};
        jdbc.query("""
                select ur.role, r.is_staff, rp.permission_code
                from user_roles ur
                join roles r on r.code = ur.role
                left join role_permissions rp on rp.role_code = ur.role
                where ur.user_id = ?""", rs -> {
            roles.add(rs.getString(1));
            boolean isStaff = rs.getBoolean(2);
            staff[0] |= isStaff;
            String p = rs.getString(3);
            if (p != null) {
                (isStaff ? staffPermissions : customerPermissions).add(p);
            }
        }, userId);
        Set<String> permissions = staff[0] ? staffPermissions : customerPermissions;
        return new Authorities(Set.copyOf(roles), Set.copyOf(permissions), staff[0]);
    }

    private record Cached(Authorities value, long loadedAt) {}
}
