package com.rentgg;

import com.rentgg.approval.domain.ApprovalRequest;
import com.rentgg.shared.security.Permission;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static RBAC guarantees (no database): staff endpoints always declare a permission, every permission named in
 * an annotation exists, and the catalog in code matches the one seeded by the migration.
 */
class AuthorizationCoverageTest {

    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");
    private static final Pattern HAS_ANY = Pattern.compile("hasAnyAuthority\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final Pattern PREFIX = Pattern.compile("anyWithPrefix\\(authentication, '([^']+)'\\)");
    private static final Set<String> CODES =
            Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());

    record Endpoint(String path, Method method, String expression) {}

    private static List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (JavaClass jc : new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.rentgg")) {
            if (!jc.isAnnotatedWith(RestController.class)) {
                continue;
            }
            Class<?> c = jc.reflect();
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            PreAuthorize classLevel = c.getAnnotation(PreAuthorize.class);
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                String path = prefix + (rm.path().length == 0 ? "" : rm.path()[0]);
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                String expr = pa != null ? pa.value() : classLevel != null ? classLevel.value() : null;
                out.add(new Endpoint(path, m, expr));
            }
        }
        return out;
    }

    @Test
    void everyStaffEndpointDeclaresAPermission() {
        List<Endpoint> all = endpoints();
        assertThat(all).hasSizeGreaterThan(40);
        List<String> unguarded = all.stream()
                .filter(e -> e.path().matches("/api/v1/(admin|support|finance|staff)(/.*)?"))
                .filter(e -> e.expression() == null)
                .map(e -> e.path() + " -> " + e.method().getDeclaringClass().getSimpleName() + "." + e.method().getName())
                .toList();
        assertThat(unguarded).as("staff endpoints without @PreAuthorize").isEmpty();
    }

    @Test
    void annotationsOnlyReferenceKnownPermissions() {
        Set<String> unknown = new TreeSet<>();
        for (Endpoint e : endpoints()) {
            if (e.expression() == null) {
                continue;
            }
            assertThat(e.expression()).as(e.path()).doesNotContain("hasRole", "hasAnyRole");
            Matcher m = HAS_AUTHORITY.matcher(e.expression());
            while (m.find()) {
                if (!CODES.contains(m.group(1))) {
                    unknown.add(m.group(1));
                }
            }
            Matcher any = HAS_ANY.matcher(e.expression());
            while (any.find()) {
                Matcher q = QUOTED.matcher(any.group(1));
                while (q.find()) {
                    if (!CODES.contains(q.group(1))) {
                        unknown.add(q.group(1));
                    }
                }
            }
            Matcher p = PREFIX.matcher(e.expression());
            while (p.find()) {
                String prefix = p.group(1);
                assertThat(CODES).as("prefix " + prefix).anyMatch(c -> c.startsWith(prefix));
            }
        }
        assertThat(unknown).isEmpty();
    }

    @Test
    void everyApprovalTypeHasRequestAndDecidePermissions() {
        for (ApprovalRequest.ActionType t : ApprovalRequest.ActionType.values()) {
            assertThat(CODES).contains(Permission.approvalRequest(t.name()), Permission.approvalDecide(t.name()));
        }
    }

    @Test
    void permissionCodesAreWellFormedAndUnique() {
        for (Permission p : Permission.values()) {
            assertThat(p.code()).matches("^[a-z_]+:[A-Za-z_:]+$");
            assertThat(p.resource()).isNotBlank();
            assertThat(p.action()).isNotBlank();
        }
        assertThat(CODES).hasSize(Permission.values().length);
    }

    @Test
    void migrationSeedsExactlyTheCatalog() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V8__rbac_roles_permissions.sql"),
                StandardCharsets.UTF_8);
        String block = sql.substring(sql.indexOf("INSERT INTO permissions"), sql.indexOf("-- Same access"));
        Pattern code = Pattern.compile("\\('([a-z_]+:[A-Za-z_:]+)',");
        Matcher m = code.matcher(block);
        Set<String> seeded = new TreeSet<>();
        while (m.find()) {
            seeded.add(m.group(1));
        }
        // Permissions added after V8 are seeded by their own migration.
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path f : files.filter(p -> !p.getFileName().toString().startsWith("V8__")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                int at = s.indexOf("INSERT INTO permissions");
                if (at < 0) {
                    continue;
                }
                Matcher later = code.matcher(s.substring(at, s.indexOf(';', at)));
                while (later.find()) {
                    seeded.add(later.group(1));
                }
            }
        }
        assertThat(seeded).isEqualTo(new TreeSet<>(CODES));
    }
}
