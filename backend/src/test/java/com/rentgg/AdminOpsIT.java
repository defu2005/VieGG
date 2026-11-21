package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff endpoints of Dev Guide §3 mục 11: verify-recovery queue, user list, audit lookup. */
class AdminOpsIT extends IntegrationTest {

    @Test
    void verifyRecoveryQueueListsPendingAccountsUntilVerified() throws Exception {
        UUID gameId = game();
        UUID owner = owner();
        UUID acc = gameAccounts.create(owner, gameId, "VN", "qq_login", "first-pass", true, true, true).id();
        String admin = bearer(user("ADMIN"));

        mvc.perform(get("/api/v1/admin/game-accounts").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(acc.toString())));

        gameAccounts.verifyRecovery(user("ADMIN"), acc);
        String after = mvc.perform(get("/api/v1/admin/game-accounts").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(after).doesNotContain(acc.toString());
    }

    @Test
    void userListFindsUserById() throws Exception {
        UUID target = user();
        mvc.perform(get("/api/v1/staff/users").param("q", target.toString())
                        .header("Authorization", bearer(user("SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(target.toString())));
    }

    @Test
    void auditLookupReturnsEntriesForAnEntityWithoutIpOrUserAgent() throws Exception {
        UUID gameId = game();
        UUID owner = owner();
        UUID acc = gameAccounts.create(owner, gameId, "VN", "aa_login", "first-pass", true, true, true).id();
        gameAccounts.verifyRecovery(user("ADMIN"), acc);

        String body = mvc.perform(get("/api/v1/admin/audit").param("entityId", acc.toString())
                        .param("action", "RECOVERY_VERIFIED")
                        .header("Authorization", bearer(user("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RECOVERY_VERIFIED")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"entityType\":\"GameAccount\"").doesNotContain("\"ip\"", "\"userAgent\"");
        // after is a JSON object, not an escaped string.
        assertThat(body).contains("\"after\":{").doesNotContain("\"after\":\"{");
        // after is an object, not an escaped string.
        assertThat(body).containsPattern("\"after\":\\{");
        assertThat(body).doesNotContain("\"after\":\"{");
    }

    @Test
    void staffEndpointsRefuseRenters() throws Exception {
        String renter = bearer(user());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", renter)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/game-accounts").header("Authorization", renter)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/staff/users").header("Authorization", renter)).andExpect(status().isForbidden());
    }
}
