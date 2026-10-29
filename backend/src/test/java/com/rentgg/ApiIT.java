package com.rentgg;

import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.shared.crypto.Hmac;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level checks: status codes, error format, authorization, webhook signature, credentials window. */
class ApiIT extends IntegrationTest {

    private static final String SECRET = "test-only-webhook-secret-rentgg-0123456789";

    /** T10: only the renter, only IN_RENTAL inside the window, 10 views/hour, every view audited, no staff access. */
    @Test
    void credentialsWindow() throws Exception {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 60);
        String url = "/api/v1/orders/" + o.id() + "/credentials";

        mvc.perform(get(url).header("Authorization", bearer(renter)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_WINDOW_CLOSED"));

        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.IN_RENTAL);

        mvc.perform(get(url).header("Authorization", bearer(renter)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.password").value("secret-pass-1"))
                .andExpect(jsonPath("$.complaintWindowEndsAt").exists());
        assertThat(reload(o).credentialFirstViewedAt()).isEqualTo(now());

        // Another user and the owner: 404; staff accounts have no customer permissions at all: 403. Never the password.
        for (UUID other : new UUID[]{user(), s.ownerId()}) {
            mvc.perform(get(url).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        }
        mvc.perform(get(url).header("Authorization", bearer(user("SUPPORT", "ADMIN")))).andExpect(status().isForbidden());
        for (int i = 2; i <= 10; i++) {
            mvc.perform(get(url).header("Authorization", bearer(renter))).andExpect(status().isOk());
        }
        mvc.perform(get(url).header("Authorization", bearer(renter)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'CREDENTIAL_VIEWED' and entity_id = ?",
                Integer.class, o.id())).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_id = ? and after::text like '%secret-pass%'",
                Integer.class, o.id())).isZero();

        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
    }

    /** T8: a top-up webhook credits the wallet once even if repeated; bad signature → 401 and nothing changes. */
    @Test
    void webhookSignatureAndIdempotency() throws Exception {
        UUID user = user();
        var intent = payments.createTopup(user, 250_000, UUID.randomUUID().toString());
        String body = "{\"providerRef\":\"" + intent.providerRef() + "\",\"status\":\"SUCCEEDED\",\"amount\":" + intent.amount() + "}";
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "deadbeef").content(raw))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        assertThat(walletBalance(user)).isZero();

        String sig = Hmac.sha256Hex(SECRET, raw);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/payments/webhook/fake").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sig).content(raw)).andExpect(status().isOk());
        }
        assertThat(walletBalance(user)).isEqualTo(250_000);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where reference = ? and type = 'WALLET_TOPUP'",
                Integer.class, "intent:" + intent.id())).isEqualTo(1);
        assertThat(totalLedger()).isZero();
    }

    /** Orders are paid from the wallet: not enough balance → refused, nothing written; a double pay is refused. */
    @Test
    void orderIsPaidFromTheWallet() throws Exception {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = order(renter, s, Duration.ofHours(3), 60);
        topup(renter, 100_000);
        assertApi(() -> orders.pay(renter, o.id(), UUID.randomUUID().toString()), "INSUFFICIENT_WALLET_BALANCE");
        assertThat(reload(o).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(walletBalance(renter)).isEqualTo(100_000);

        topup(renter, 200_000);
        String key = UUID.randomUUID().toString();
        var paid = orders.pay(renter, o.id(), key);
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(orders.pay(renter, o.id(), key).id()).isEqualTo(paid.id()); // replay
        assertApi(() -> orders.pay(renter, o.id(), UUID.randomUUID().toString()), "INVALID_STATE");
        assertThat(reload(o).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(walletBalance(renter)).isEqualTo(300_000 - o.amountToPay());

        // Paying after the hold expired is refused and the wallet is untouched.
        OrderView late = order(renter, s, Duration.ofHours(6), 60);
        advanceAndRun(Duration.ofMinutes(20));
        assertThat(reload(late).status()).isEqualTo(OrderStatus.EXPIRED);
        assertApi(() -> orders.pay(renter, late.id(), UUID.randomUUID().toString()), "INVALID_STATE");
        assertThat(walletBalance(renter)).isEqualTo(300_000 - o.amountToPay());
        assertThat(totalLedger()).isZero();
    }

    @Test
    void authAndErrors() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        UUID u = user();
        mvc.perform(get("/api/v1/admin/approvals").header("Authorization", bearer(u))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"" + UUID.randomUUID() + "\",\"startAt\":\"2030-01-01T00:00:00Z\",\"durationMinutes\":60}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"kid" + UUID.randomUUID() + "@x.dev\",\"password\":\"password-1234\",\"displayName\":\"Kid\",\"dateOfBirth\":\"" + now().toString().substring(0, 4) + "-01-01\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGE_RESTRICTED"));
        // Register + login + refresh rotation + reuse detection.
        String email = "me" + UUID.randomUUID() + "@x.dev";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password-1234\",\"displayName\":\"Me\",\"dateOfBirth\":\"1990-01-01\"}"))
                .andExpect(status().isCreated());
        var t1 = auth.login(email, "password-1234");
        var t2 = auth.refresh(t1.refreshToken());
        assertApi(() -> auth.refresh(t1.refreshToken()), "UNAUTHENTICATED");
        assertApi(() -> auth.refresh(t2.refreshToken()), "UNAUTHENTICATED"); // whole family revoked
        assertApi(() -> auth.login(email, "wrong-password"), "INVALID_CREDENTIALS");
        mvc.perform(get("/api/v1/listings/search")).andExpect(status().isOk());
    }

    @Test
    void accountSelfService() throws Exception {
        String email = "acc" + UUID.randomUUID() + "@x.dev";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password-1234\",\"displayName\":\"Old\",\"dateOfBirth\":\"1990-01-01\"}"))
                .andExpect(status().isCreated());
        var t1 = auth.login(email, "password-1234");
        var t2 = auth.login(email, "password-1234");
        String bearer = "Bearer " + t1.accessToken();

        // Update display name (trimmed); blank is rejected.
        mvc.perform(put("/api/v1/me").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  New Name \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("New Name"));
        mvc.perform(put("/api/v1/me").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // Logout revokes only this device's refresh token; it is public and idempotent.
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + t2.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isNoContent());
        assertApi(() -> auth.refresh(t2.refreshToken()), "UNAUTHENTICATED");

        // Change password: wrong current password, same password, then success revokes the other sessions.
        mvc.perform(post("/api/v1/me/password").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"password-5678\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        mvc.perform(post("/api/v1/me/password").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password-1234\",\"newPassword\":\"password-1234\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
        mvc.perform(post("/api/v1/me/password").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password-1234\",\"newPassword\":\"password-5678\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").isNotEmpty());
        assertApi(() -> auth.refresh(t1.refreshToken()), "UNAUTHENTICATED");
        assertApi(() -> auth.login(email, "password-1234"), "INVALID_CREDENTIALS");
        var t3 = auth.login(email, "password-5678");
        var t4 = auth.login(email, "password-5678");

        // Logout everywhere revokes every refresh token.
        mvc.perform(post("/api/v1/me/logout-all").header("Authorization", "Bearer " + t3.accessToken()))
                .andExpect(status().isNoContent());
        assertApi(() -> auth.refresh(t3.refreshToken()), "UNAUTHENTICATED");
        assertApi(() -> auth.refresh(t4.refreshToken()), "UNAUTHENTICATED");
    }

    @Test
    void noStaffEndpointExposesPasswords() throws Exception {
        String docs = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/v1/orders/{id}/credentials");
        // The only path returning credentials is the renter endpoint; no admin/support/finance path mentions them.
        assertThat(docs).doesNotContainPattern("/api/v1/(admin|support|finance)/[^\"]*credential");
    }
}
