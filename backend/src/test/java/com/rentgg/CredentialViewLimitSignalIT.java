package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An abuse signal when the credentials view limit is hit: one per user and order per hour (§12.5, Dev Guide §3 mục 7). */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CredentialViewLimitSignalIT extends IntegrationTest {

    private static final String TYPE = "CREDENTIAL_VIEW_RATE_LIMITED";

    private long signals(UUID user, UUID order) {
        return jdbc.queryForObject("select count(*) from abuse_signals where user_id = ? and type = ? and order_id = ?",
                Long.class, user, TYPE, order);
    }

    @Test
    void tenViewsRecordNoSignalTheEleventhRecordsOneAndTheTwelfthStillOne() throws Exception {
        var s = standardListing();
        UUID renter = user();
        var o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));

        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/orders/" + o.id() + "/credentials").header("Authorization", bearer(renter)))
                    .andExpect(status().isOk());
        }
        assertThat(signals(renter, o.id())).isZero();

        mvc.perform(get("/api/v1/orders/" + o.id() + "/credentials").header("Authorization", bearer(renter)))
                .andExpect(status().isTooManyRequests());
        assertThat(signals(renter, o.id())).isEqualTo(1);

        mvc.perform(get("/api/v1/orders/" + o.id() + "/credentials").header("Authorization", bearer(renter)))
                .andExpect(status().isTooManyRequests());
        assertThat(signals(renter, o.id())).isEqualTo(1);

        // Support sees the signal through the existing admin endpoint.
        UUID support = user("SUPPORT");
        mvc.perform(get("/api/v1/admin/users/" + renter + "/abuse-signals").header("Authorization", bearer(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == '" + TYPE + "')]").exists());
    }
}
