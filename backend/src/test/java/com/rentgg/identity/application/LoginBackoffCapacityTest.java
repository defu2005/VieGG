package com.rentgg.identity.application;

import com.rentgg.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A flood of distinct keys cannot reset the escalated wait of a real target (fills the map to its cap). */
class LoginBackoffCapacityTest {

    @Test
    void floodOfFreshKeysDoesNotResetAnEscalatedTarget() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        LoginBackoff backoff = new LoginBackoff(Clock.fixed(now, ZoneOffset.UTC));

        String target = "victim@example.dev";
        for (int i = 0; i < LoginBackoff.FREE_FAILURES + 2; i++) {
            backoff.recordWrongPassword(target);
        }
        assertThatThrownBy(() -> backoff.checkBeforePassword(target)).isInstanceOf(ApiException.class);

        // Random emails, one wrong password each: enough to fill the map past its cap.
        for (int i = 0; i < LoginBackoff.MAX_ENTRIES + 5_000; i++) {
            backoff.recordWrongPassword("flood" + i + "@example.dev");
        }

        assertThatThrownBy(() -> backoff.checkBeforePassword(target))
                .as("target still waits after the flood")
                .isInstanceOf(ApiException.class);
        assertThat(size(backoff)).isLessThanOrEqualTo(LoginBackoff.MAX_ENTRIES);
    }

    private static int size(LoginBackoff backoff) {
        try {
            var f = LoginBackoff.class.getDeclaredField("states");
            f.setAccessible(true);
            return ((java.util.Map<?, ?>) f.get(backoff)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
