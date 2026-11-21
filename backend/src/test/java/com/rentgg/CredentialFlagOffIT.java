package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With rentgg.credentials.require-reauth off (default context), the password on POST is ignored; both answers are no-store. */
class CredentialFlagOffIT extends IntegrationTest {

    @Test
    void postReturnsCredentialsWhateverPasswordIsSentAndBothRoutesAreNoStore() throws Exception {
        var s = standardListing();
        UUID renter = user();
        var o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));

        mvc.perform(get("/api/v1/orders/" + o.id() + "/credentials").header("Authorization", bearer(renter)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        mvc.perform(post("/api/v1/orders/" + o.id() + "/credentials").header("Authorization", bearer(renter))
                        .contentType("application/json").content("{\"password\":\"anything-at-all\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        assertThat(jdbc.queryForObject("select credential_first_viewed_at is not null from orders where id = ?",
                Boolean.class, o.id())).isTrue();
    }
}
