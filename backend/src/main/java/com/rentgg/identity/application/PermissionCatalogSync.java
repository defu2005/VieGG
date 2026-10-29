package com.rentgg.identity.application;

import com.rentgg.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the {@code permissions} table equal to the {@link Permission} catalog in code: inserts new codes, refreshes
 * descriptions, and warns about codes that only exist in the database. New permissions are not given to any role
 * here; grant them with a migration or the RBAC admin API.
 */
@Component
@Order(0)
public class PermissionCatalogSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalogSync.class);

    private final JdbcTemplate jdbc;

    public PermissionCatalogSync(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync();
    }

    public void sync() {
        for (Permission p : Permission.values()) {
            jdbc.update("""
                    insert into permissions (code, resource, action, description) values (?, ?, ?, ?)
                    on conflict (code) do update set resource = excluded.resource, action = excluded.action,
                                                     description = excluded.description""",
                    p.code(), p.resource(), p.action(), p.description());
        }
        Set<String> known = Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());
        Set<String> stale = new HashSet<>(jdbc.queryForList("select code from permissions", String.class));
        stale.removeAll(known);
        if (!stale.isEmpty()) {
            log.warn("Permissions in the database but not in code (no longer checked anywhere): {}", stale);
        }
    }
}
