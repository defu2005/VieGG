package com.rentgg;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks deploy/db/roles.sql on a real PostgreSQL: Flyway runs as rentgg_migrator from an empty database, and
 * rentgg_app can read and write but cannot run DDL or TRUNCATE. Plain JDBC, no Spring context.
 * Uses TEST_DB_URL to create a scratch database rentgg_roles_test (roles are cluster-wide).
 * Without the admin privilege to create roles it is skipped locally and fails in CI.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf(value = "com.rentgg.DbRolesIT#adminCanCreateRolesOrCi",
        disabledReason = "TEST_DB_URL is not set, PostgreSQL is unreachable, or the admin cannot create roles")
class DbRolesIT {

    private static final String ROLES_DB = "rentgg_roles_test";
    private static final String MIGRATOR = "rentgg_migrator";
    private static final String APP = "rentgg_app";
    // Test-only literals; never used outside this scratch cluster.
    private static final String MIGRATOR_PW = "test-only-migrator-pw";
    private static final String APP_PW = "test-only-app-pw";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    private static String adminUrl() {
        return env("TEST_DB_URL", null);
    }

    private static String roleDbUrl() {
        return adminUrl().replaceFirst("/[^/]*$", "/" + ROLES_DB);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Connection admin(String url) throws SQLException {
        return DriverManager.getConnection(url, env("TEST_DB_USERNAME", "rentgg"), env("TEST_DB_PASSWORD", "rentgg_dev"));
    }

    private static Connection as(String user, String password) throws SQLException {
        return DriverManager.getConnection(roleDbUrl(), user, password);
    }

    /**
     * Outside CI the class is disabled (with a reason shown in the report) when there is no reachable admin who can
     * create roles. In CI it is never disabled, so a missing server or privilege fails the build.
     */
    static boolean adminCanCreateRolesOrCi() {
        if (System.getenv("CI") != null) {
            return true;
        }
        String url = adminUrl();
        if (url == null) {
            return false;
        }
        try (Connection c = admin(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select rolcreaterole or rolsuper from pg_roles where rolname = current_user")) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeAll
    static void createScratchDatabaseWithRoles() throws Exception {
        String url = adminUrl();
        if (url == null) {
            throw new IllegalStateException("DbRolesIT needs TEST_DB_URL (CI sets it)");
        }
        try (Connection c = admin(url); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("select rolcreaterole or rolsuper from pg_roles where rolname = current_user");
            rs.next();
            if (!rs.getBoolean(1)) {
                throw new IllegalStateException("The admin role must be able to create roles");
            }
            st.execute("DROP DATABASE IF EXISTS " + ROLES_DB + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + ROLES_DB);
        }
        try (Connection c = admin(roleDbUrl()); Statement st = c.createStatement()) {
            // One execute: the script has $$ blocks that a statement splitter would break.
            st.execute(Files.readString(Path.of("../deploy/db/roles.sql")));
            st.execute("ALTER ROLE " + MIGRATOR + " WITH LOGIN PASSWORD '" + MIGRATOR_PW + "'");
            st.execute("ALTER ROLE " + APP + " WITH LOGIN PASSWORD '" + APP_PW + "'");
        }
    }

    @Test
    @Order(1)
    void migratorRunsFlywayFromEmptyDatabase() {
        // The discriminating step: V1 creates btree_gist, which needs CREATE on the database.
        Flyway.configure()
                .dataSource(roleDbUrl(), MIGRATOR, MIGRATOR_PW)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @Order(2)
    void flywayHistoryIsWrittenByMigrator() throws SQLException {
        try (Connection c = as(MIGRATOR, MIGRATOR_PW); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select distinct installed_by from flyway_schema_history")) {
            List<String> who = new ArrayList<>();
            while (rs.next()) {
                who.add(rs.getString(1));
            }
            assertThat(who).containsExactly(MIGRATOR);
        }
    }

    @Test
    @Order(3)
    void appHasDmlButNoTruncateOnEveryPublicTable() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = admin(roleDbUrl()); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select tablename from pg_tables where schemaname = 'public'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables).contains("users", "orders");
        try (Connection c = admin(roleDbUrl()); Statement st = c.createStatement()) {
            for (String t : tables) {
                ResultSet rs = st.executeQuery("select has_table_privilege('" + APP + "', 'public." + t + "', 'SELECT'),"
                        + " has_table_privilege('" + APP + "', 'public." + t + "', 'INSERT'),"
                        + " has_table_privilege('" + APP + "', 'public." + t + "', 'UPDATE'),"
                        + " has_table_privilege('" + APP + "', 'public." + t + "', 'DELETE'),"
                        + " has_table_privilege('" + APP + "', 'public." + t + "', 'TRUNCATE')");
                rs.next();
                assertThat(rs.getBoolean(1)).as("SELECT on %s", t).isTrue();
                assertThat(rs.getBoolean(2)).as("INSERT on %s", t).isTrue();
                assertThat(rs.getBoolean(3)).as("UPDATE on %s", t).isTrue();
                assertThat(rs.getBoolean(4)).as("DELETE on %s", t).isTrue();
                assertThat(rs.getBoolean(5)).as("TRUNCATE on %s", t).isFalse();
            }
        }
    }

    @Test
    @Order(4)
    void appCanReadAndWriteButNotRunDdlOrTruncate() throws SQLException {
        try (Connection c = as(APP, APP_PW); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("select count(*) from users");
            assertThat(rs.next()).isTrue();
        }
        assertDenied("TRUNCATE users");
        assertDenied("DROP TABLE users");
        assertDenied("CREATE TABLE app_probe (id int)");
        assertDenied("ALTER TABLE users ADD COLUMN app_probe int");
    }

    @Test
    @Order(5)
    void migratorStillCanTruncateItsOwnTable() throws SQLException {
        try (Connection c = as(MIGRATOR, MIGRATOR_PW); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE migrator_probe (id int)");
            st.execute("TRUNCATE migrator_probe");
            st.execute("DROP TABLE migrator_probe");
        }
    }

    private static void assertDenied(String sql) {
        assertThatThrownBy(() -> {
            try (Connection c = as(APP, APP_PW); Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as("SQLState for: %s", sql).isEqualTo(SQLSTATE_INSUFFICIENT_PRIVILEGE));
    }
}
