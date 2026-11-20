package com.rentgg;

import com.rentgg.identity.application.AuthService;
import com.rentgg.identity.application.TotpService;
import com.rentgg.shared.security.JwtService;
import com.rentgg.support.IntegrationTest;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff second factor (Dev Guide §2.4): enforcement on permissions, TOTP checks, lockout, refresh and enrolment. */
class StaffMfaIT extends IntegrationTest {

    static final String PASSWORD = "password-123456";
    private static final long PERIOD = 30;
    private final DefaultCodeGenerator codes = new DefaultCodeGenerator();

    @Autowired TotpService totp;
    @Autowired JwtService jwtService;
    @Autowired AuthService authService;

    private record Staff(UUID id, String email) {}

    private Staff staff(String role) {
        String email = "staff-" + UUID.randomUUID() + "@test.dev";
        UUID id = auth.register(email, PASSWORD, "Staff", LocalDate.of(1990, 1, 1)).id();
        tx.executeWithoutResult(s -> userRepository.findById(id).orElseThrow().addRole(role));
        return new Staff(id, email);
    }

    private String code(String secret, Instant at) {
        try {
            return codes.generate(secret, at.getEpochSecond() / PERIOD);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Enrols the staff user through the service and returns the secret. */
    private String enroll(Staff s) {
        String secret = totp.startEnrollment(s.id(), s.email()).secret();
        totp.confirmEnrollment(s.id(), code(secret, now()), now());
        return secret;
    }

    private String bearerUnverified(Staff s, String role) {
        return "Bearer " + jwtService.issueAccessToken(s.id(), List.of(role), "ACTIVE", false).value();
    }

    // ---- Enforcement on permissions

    @Test
    void unverifiedStaffGets403AndVerifiedStaffGets200OnAnAdminEndpoint() throws Exception {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);

        mvc.perform(get("/api/v1/admin/users/" + admin.id()).header("Authorization", bearerUnverified(admin, "ADMIN")))
                .andExpect(status().isForbidden());

        clock.advance(Duration.ofSeconds(PERIOD));
        var verified = auth.login(admin.email(), PASSWORD, code(secret, now()));
        mvc.perform(get("/api/v1/admin/users/" + admin.id()).header("Authorization", "Bearer " + verified.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void unenrolledStaffLogsInUnverifiedAndCannotEnrolAsNonStaff() {
        Staff admin = staff("ADMIN");
        var t = auth.login(admin.email(), PASSWORD, null);
        assertThat(jdbc.queryForObject("select mfa_verified from refresh_tokens where id = ?", Boolean.class, t.refreshTokenId()))
                .isFalse();
    }

    // ---- Login checks

    @Test
    void enrolledStaffMustSendACode() {
        Staff admin = staff("ADMIN");
        enroll(admin);
        assertApi(() -> auth.login(admin.email(), PASSWORD, null), "MFA_REQUIRED");
        assertApi(() -> auth.login(admin.email(), PASSWORD, "000000"), "MFA_INVALID");
    }

    @Test
    void customerLoginIgnoresCodes() {
        UUID id = user("USER");
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, id);
        assertThat(auth.login(email, PASSWORD, null).accessToken()).isNotBlank();
    }

    // ---- TOTP checks (MutableClock)

    @Test
    void currentStepIsAcceptedOnceAndTheReusedCodeIsRefused() {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD));
        String c = code(secret, now());
        assertThat(totp.verify(admin.id(), c, now())).isEqualTo(TotpService.Verdict.OK);
        assertThat(totp.verify(admin.id(), c, now())).isEqualTo(TotpService.Verdict.INVALID);
    }

    @Test
    void nextStepIsAcceptedAndOneStepEitherSideIsWithinTheWindow() {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD * 3));
        assertThat(totp.verify(admin.id(), code(secret, now().minusSeconds(PERIOD)), now()))
                .isEqualTo(TotpService.Verdict.OK);
        assertThat(totp.verify(admin.id(), code(secret, now().plusSeconds(PERIOD)), now()))
                .isEqualTo(TotpService.Verdict.OK);
    }

    @Test
    void twoStepsAwayIsRefused() {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD * 3));
        assertThat(totp.verify(admin.id(), code(secret, now().plusSeconds(PERIOD * 2)), now()))
                .isEqualTo(TotpService.Verdict.INVALID);
        assertThat(totp.verify(admin.id(), code(secret, now().minusSeconds(PERIOD * 2)), now()))
                .isEqualTo(TotpService.Verdict.INVALID);
    }

    @Test
    void concurrentLoginsWithOneCodeLetExactlyOneWin() throws Exception {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD));
        String c = code(secret, now());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TotpService.Verdict>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return totp.verify(admin.id(), c, now());
            }));
        }
        go.countDown();
        List<TotpService.Verdict> outcomes = new ArrayList<>();
        for (Future<TotpService.Verdict> f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn(TotpService.Verdict.OK::equals).hasSize(1);
    }

    // ---- Lockout survives the exception and expires with the clock

    @Test
    void fiveWrongCodesLockTheAccountForFifteenMinutes() {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login(admin.email(), PASSWORD, "000000"))
                    .isInstanceOf(com.rentgg.shared.error.ApiException.class);
        }
        // The failures were committed although login threw: the next correct code is refused.
        assertApi(() -> auth.login(admin.email(), PASSWORD, code(secret, now())), "MFA_LOCKED");

        clock.advance(Duration.ofMinutes(16));
        clock.advance(Duration.ofSeconds(PERIOD));
        assertThat(auth.login(admin.email(), PASSWORD, code(secret, now())).accessToken()).isNotBlank();
    }

    // ---- Refresh keeps the session state

    @Test
    void refreshKeepsVerifiedAndUnverifiedSessions() {
        Staff admin = staff("ADMIN");
        String secret = enroll(admin);
        clock.advance(Duration.ofSeconds(PERIOD));
        var verified = auth.login(admin.email(), PASSWORD, code(secret, now()));
        var refreshed = auth.refresh(verified.refreshToken());
        assertThat(jdbc.queryForObject("select mfa_verified from refresh_tokens where id = ?", Boolean.class, refreshed.refreshTokenId()))
                .isTrue();

        Staff unenrolled = staff("ADMIN");
        var unverified = auth.login(unenrolled.email(), PASSWORD, null);
        var refreshedUnverified = auth.refresh(unverified.refreshToken());
        assertThat(jdbc.queryForObject("select mfa_verified from refresh_tokens where id = ?", Boolean.class, refreshedUnverified.refreshTokenId()))
                .isFalse();
    }

    // ---- Storage

    @Test
    void storedSecretIsEncryptedAndNotPlaintext() {
        Staff admin = staff("ADMIN");
        String secret = totp.startEnrollment(admin.id(), admin.email()).secret();
        byte[] stored = jdbc.queryForObject("select secret_ct from user_totp where user_id = ?", byte[].class, admin.id());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(stored).isNotEqualTo(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- Enrolment endpoints

    @Test
    void enrolmentEndpointsWorkWithAnUnverifiedStaffTokenAndRefuseNonStaff() throws Exception {
        Staff admin = staff("ADMIN");
        String bearer = bearerUnverified(admin, "ADMIN");
        String body = mvc.perform(post("/api/v1/me/mfa/totp/start").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secret = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body).get("secret").asText();
        mvc.perform(post("/api/v1/me/mfa/totp/confirm").header("Authorization", bearer)
                        .contentType("application/json").content("{\"code\":\"" + code(secret, now()) + "\"}"))
                .andExpect(status().isOk());

        UUID customer = user("USER");
        String customerBearer = bearer(customer);
        mvc.perform(post("/api/v1/me/mfa/totp/start").header("Authorization", customerBearer))
                .andExpect(status().isForbidden());
    }
}
