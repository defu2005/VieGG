package com.rentgg.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the staging and prod checks: the real context with mock values that pass StartupSafetyCheck.
 * Runs against TEST_DB_URL; the database is already migrated, so nothing is cleaned.
 */
@SpringBootTest(properties = {
        "rentgg.security.jwt-secret=" + ApiDocsDisabledTest.JWT,
        "rentgg.payment.webhook-secret=webhook-secret-for-tests-0123456789abcdef",
        "rentgg.credentials.active-key-id=k202611",
        "rentgg.credentials.keys=k202611:" + ApiDocsDisabledTest.JWT,
        "rentgg.security.cors-allowed-origins=https://rentgg.vn",
        "rentgg.payment.public-base-url=https://rentgg.vn",
        "rentgg.payment.frontend-base-url=https://rentgg.vn",
        "rentgg.payment.provider=PAYOS",
        "rentgg.payment.fake-enabled=false",
        "rentgg.payment.payos.client-id=3f8c2a91-client",
        "rentgg.payment.payos.api-key=a1b2c3d4-apikey",
        "rentgg.payment.payos.checksum-key=9f8e7d6c-checksum",
        "rentgg.media.storage=db",
        "rentgg.jobs.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+", disabledReason = "TEST_DB_URL is not set")
abstract class ApiDocsDisabledTest {

    /** 32 bytes, base64. Not the dev default, and not a placeholder. */
    static final String JWT = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired protected MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        TestDbProperties.register(r);
    }

    protected void assertDocsAreNotServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
