package com.rentgg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.identity.application.TotpService;
import com.rentgg.support.IntegrationTest;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Progressive wait after wrong passwords (§12.5) on the HTTP login endpoint. Each test uses its own email. */
class LoginBackoffIT extends IntegrationTest {

    private static final String PASSWORD = "password-123456";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TotpService totp;

    private String newEmail() {
        String email = "backoff-" + UUID.randomUUID() + "@test.dev";
        auth.register(email, PASSWORD, "Backoff", LocalDate.of(1990, 1, 1));
        return email;
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("email", email, "password", password)))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        return mvc.perform(req);
    }

    private ResultActions login(String email, String password) throws Exception {
        return login(email, password, "10.1.1.1");
    }

    /** The retryAfterSeconds of the 429 answer. */
    private long retryAfter(ResultActions res) throws Exception {
        JsonNode body = JSON.readTree(res.andReturn().getResponse().getContentAsString());
        return body.path("details").path("retryAfterSeconds").asLong();
    }

    @Test
    void fiveWrongAreFreeThenTheRightPasswordWaitsThenSucceedsAfterTheClockMoves() throws Exception {
        String email = newEmail();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-" + i).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        // The sixth attempt is wrong too: it counts and starts the first wait.
        login(email, "wrong-6").andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        clock.advance(Duration.ofSeconds(31));
        login(email, PASSWORD).andExpect(status().isOk());

        // Reset by the successful login: one wrong password is an ordinary 401 again.
        login(email, "wrong-again").andExpect(status().isUnauthorized());
    }

    @Test
    void waitsStrictlyIncreaseAndTheCapHolds() throws Exception {
        String email = newEmail();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-" + i);
        }
        long previous = 0;
        for (int k = 0; k < 3; k++) {
            login(email, "wrong-x").andExpect(status().isUnauthorized());
            long wait = retryAfter(login(email, PASSWORD));
            assertThat(wait).isGreaterThan(previous);
            previous = wait;
            clock.advance(Duration.ofSeconds(wait + 1));
        }
        assertThat(previous).isEqualTo(120);

        // Keep failing: the wait never exceeds 15 minutes.
        for (int k = 0; k < 12; k++) {
            login(email, "wrong-y");
            clock.advance(Duration.ofSeconds(901));
        }
        login(email, "wrong-z");
        assertThat(retryAfter(login(email, PASSWORD))).isLessThanOrEqualTo(900);
    }

    @Test
    void anUnknownEmailFollowsTheSameSequence() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@test.dev";
        for (int i = 0; i < 5; i++) {
            login(unknown, "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login(unknown, "wrong-6").andExpect(status().isUnauthorized());
        login(unknown, "wrong-7").andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherIpOrAnotherEmailIsUnaffected() throws Exception {
        String email = newEmail();
        for (int i = 0; i < 6; i++) {
            login(email, "wrong-" + i);
        }
        login(email, "wrong-7", "10.2.2.2").andExpect(status().isUnauthorized());
        login(newEmail(), "wrong").andExpect(status().isUnauthorized());
        login(email, PASSWORD, "10.2.2.2").andExpect(status().isOk());
    }

    /** MFA_REQUIRED neither counts nor locks: six attempts without a code, then the right code still works. */
    @Test
    void enrolledStaffWithoutACodeIsNeverLocked() throws Exception {
        String email = "staff-backoff-" + UUID.randomUUID() + "@test.dev";
        UUID staff = auth.register(email, PASSWORD, "Staff", LocalDate.of(1990, 1, 1)).id();
        tx.executeWithoutResult(s -> userRepository.findById(staff).orElseThrow().addRole("ADMIN"));
        String secret = totp.startEnrollment(staff, email).secret();
        DefaultCodeGenerator gen = new DefaultCodeGenerator();
        totp.confirmEnrollment(staff, gen.generate(secret, clock.instant().getEpochSecond() / 30), clock.instant());
        clock.advance(Duration.ofSeconds(30));

        for (int i = 0; i < 6; i++) {
            login(email, PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        }
        String code = gen.generate(secret, clock.instant().getEpochSecond() / 30);
        login(email, PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("email", email, "password", PASSWORD, "totpCode", code))))
                .andExpect(status().isOk());
    }
}
