package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D5: credentials view with the RentGG password re-confirmed (rentgg.credentials.require-reauth = true). One new
 * context for this class only; the pool is small and the context is closed afterwards (CI Postgres allows 100).
 */
@TestPropertySource(properties = {"rentgg.credentials.require-reauth=true", "spring.datasource.hikari.maximum-pool-size=5"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CredentialReauthIT extends IntegrationTest {

    /** Password set by the test base helper user(). */
    private static final String PASSWORD = "password-123456";

    private record Rented(UUID renter, UUID orderId, UUID listingId) {}

    private Rented rentedOrder() {
        var s = standardListing();
        UUID renter = user();
        var o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        return new Rented(renter, o.id(), s.listingId());
    }

    private String passwordBody(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private long audit(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    @Test
    void getIsRefusedWithReauthRequiredAndLeavesNoTraceOfAView() throws Exception {
        Rented r = rentedOrder();
        long viewedBefore = audit("CREDENTIAL_VIEWED");

        mvc.perform(get("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(r.renter())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        assertThat(audit("CREDENTIAL_VIEWED")).isEqualTo(viewedBefore);
        assertThat(jdbc.queryForObject("select credential_first_viewed_at from orders where id = ?",
                java.time.Instant.class, r.orderId())).isNull();
    }

    @Test
    void wrongPasswordIsRefusedAndRightPasswordReturnsCredentialsAndRecordsTheFirstView() throws Exception {
        Rented r = rentedOrder();

        mvc.perform(post("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(r.renter()))
                        .contentType("application/json").content(passwordBody("not-my-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_FAILED"));
        assertThat(audit("CREDENTIAL_VIEWED")).isZero();

        mvc.perform(post("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(r.renter()))
                        .contentType("application/json").content(passwordBody(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.password").exists());
        assertThat(audit("CREDENTIAL_VIEWED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select credential_first_viewed_at is not null from orders where id = ?",
                Boolean.class, r.orderId())).isTrue();
    }

    @Test
    void repeatedAttemptsAreLimitedPerUserAndEachWrongOneIsAudited() throws Exception {
        Rented r = rentedOrder();
        long before = audit("CREDENTIAL_VIEW_DENIED");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(r.renter()))
                            .contentType("application/json").content(passwordBody("wrong-" + i)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(r.renter()))
                        .contentType("application/json").content(passwordBody(PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(audit("CREDENTIAL_VIEW_DENIED") - before).isGreaterThanOrEqualTo(6);
    }

    @Test
    void aStrangerGets404OnBothRoutes() throws Exception {
        Rented r = rentedOrder();
        UUID stranger = user();

        mvc.perform(get("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + r.orderId() + "/credentials").header("Authorization", bearer(stranger))
                        .contentType("application/json").content(passwordBody(PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void passwordIsNotEchoedInTheRequestToString() {
        var req = new com.rentgg.order.api.OrderController.ReauthRequest("secret-pass-123");
        assertThat(req.toString()).doesNotContain("secret-pass-123");
    }
}
