package com.rentgg.docs;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a context at TEST_DB_URL, the same way IntegrationTest does. */
final class TestDbProperties {

    private TestDbProperties() {}

    static void register(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "rentgg"));
        r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "rentgg_dev"));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
