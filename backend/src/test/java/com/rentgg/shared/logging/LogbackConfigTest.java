package com.rentgg.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real logback-spring.xml through Spring Boot's logging system, once per profile, and reads what
 * reaches the console. No Spring context and no database.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackConfigTest {

    private final LogbackLoggingSystem system = new LogbackLoggingSystem(getClass().getClassLoader());

    private void init(String profile) {
        system.cleanUp();
        system.beforeInitialize();
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        system.cleanUp();
    }

    @Test
    void textOutputMasksMessageAndStackTraceInTestProfile(CapturedOutput output) {
        init("test");
        var log = LoggerFactory.getLogger("config-test");
        log.info("login ok password=hunter2 user=renter");
        log.error("refund failed token=abc123", new IllegalStateException("gateway said secret=zzz999"));

        assertThat(output.getOut()).contains("login ok password=**** user=renter");
        assertThat(output.getOut()).contains("refund failed token=****");
        assertThat(output.getOut()).contains("gateway said secret=****");
        assertThat(output.getOut()).doesNotContain("hunter2", "abc123", "zzz999");
    }

    @Test
    void textOutputCarriesTraceUserAndOrderIdInTestProfile(CapturedOutput output) {
        init("test");
        MDC.put("traceId", "t-9");
        MDC.put("userId", "3f2c0000-0000-0000-0000-000000000001");
        MDC.put("orderId", "6a7b0000-0000-0000-0000-000000000002");
        LoggerFactory.getLogger("config-test").info("order step done");

        String line = output.getOut().lines().filter(l -> l.contains("order step done")).findFirst().orElseThrow();
        assertThat(line).contains("[t-9] [3f2c0000-0000-0000-0000-000000000001] [6a7b0000-0000-0000-0000-000000000002]");
    }

    @Test
    void jsonOutputCarriesUserAndOrderIdUnmaskedInProdProfile(CapturedOutput output) throws Exception {
        init("prod");
        MDC.put("userId", "3f2c0000-0000-0000-0000-000000000001");
        MDC.put("orderId", "6a7b0000-0000-0000-0000-000000000002");
        LoggerFactory.getLogger("config-test").info("order json step");

        String line = output.getOut().lines().filter(l -> l.contains("order json step")).findFirst().orElseThrow();
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.path("userId").asText()).isEqualTo("3f2c0000-0000-0000-0000-000000000001");
        assertThat(json.path("orderId").asText()).isEqualTo("6a7b0000-0000-0000-0000-000000000002");
    }

    @Test
    void quotedValueWithSpacesDoesNotLeakInText(CapturedOutput output) {
        init("test");
        LoggerFactory.getLogger("config-test").info("{\"password\":\"correct horse battery\"}");
        assertThat(output.getOut()).contains("{\"password\":\"****\"}");
        assertThat(output.getOut()).doesNotContain("horse", "battery");
    }

    @Test
    void loggerNameIsNotTakenForAKey(CapturedOutput output) {
        init("test");
        LoggerFactory.getLogger("com.rentgg.TokenService").info("Started fine");
        assertThat(output.getOut()).contains("Started fine");
    }

    @Test
    void jsonOutputMasksMessageAndWhitelistedMdcInProdProfile(CapturedOutput output) throws Exception {
        init("prod");
        MDC.put("traceId", "t-1");
        MDC.put("requestPath", "/api/v1/x?token=abc123&page=2");
        MDC.put("token", "not-whitelisted");
        LoggerFactory.getLogger("config-test").info("login ok password=hunter2 user=renter");

        String line = output.getOut().lines().filter(l -> l.contains("login ok")).findFirst().orElseThrow();
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.path("message").asText()).isEqualTo("login ok password=**** user=renter");
        assertThat(json.path("requestPath").asText()).isEqualTo("/api/v1/x?token=****&page=2");
        assertThat(json.path("traceId").asText()).isEqualTo("t-1");
        assertThat(json.has("token")).isFalse();
        assertThat(output.getOut()).doesNotContain("hunter2", "abc123", "not-whitelisted");
    }

    @Test
    void jsonStackTraceIsMasked(CapturedOutput output) throws Exception {
        init("prod");
        LoggerFactory.getLogger("config-test").error("gateway call failed",
                new IllegalStateException("upstream rejected secret=zzz999"));

        String line = output.getOut().lines().filter(l -> l.contains("gateway call failed")).findFirst().orElseThrow();
        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.path("stack_trace").asText()).contains("secret=****");
        assertThat(output.getOut()).doesNotContain("zzz999");
    }
}
