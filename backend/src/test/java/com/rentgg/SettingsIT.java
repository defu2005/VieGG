package com.rentgg;

import com.rentgg.settings.application.SettingsService;
import com.rentgg.settings.domain.Setting;
import com.rentgg.support.IntegrationTest;
import com.rentgg.wallet.application.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin-managed runtime settings: validation, public view, permissions and withdrawal limits. */
class SettingsIT extends IntegrationTest {

    @Autowired SettingsService settings;
    @Autowired WalletService wallet;

    @AfterEach
    void resetToDefaults() {
        jdbc.update("delete from app_settings");
    }

    @Test
    void defaultsUntilChanged_thenResetWithNull() {
        UUID admin = user("ADMIN");
        assertThat(settings.getLong(Setting.WITHDRAWAL_MIN_AMOUNT)).isEqualTo(10_000);

        settings.update(admin, Map.of("withdrawal.min_amount", "20.000", "banner.message", "  Bảo trì 22h  "));
        assertThat(settings.getLong(Setting.WITHDRAWAL_MIN_AMOUNT)).isEqualTo(20_000);
        assertThat(settings.get(Setting.BANNER_MESSAGE)).isEqualTo("Bảo trì 22h");
        assertThat(settings.list()).filteredOn(v -> v.key().equals("withdrawal.min_amount"))
                .singleElement().satisfies(v -> {
                    assertThat(v.customized()).isTrue();
                    assertThat(v.updatedBy()).isEqualTo(admin);
                });
        Integer audits = jdbc.queryForObject("select count(*) from audit_logs where action = 'SETTINGS_UPDATED' "
                + "and actor_id = ?", Integer.class, admin);
        assertThat(audits).isEqualTo(1);

        Map<String, String> reset = new HashMap<>();
        reset.put("withdrawal.min_amount", null);
        settings.update(admin, reset);
        assertThat(settings.getLong(Setting.WITHDRAWAL_MIN_AMOUNT)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("select count(*) from app_settings where key = 'withdrawal.min_amount'",
                Integer.class)).isZero();
    }

    @Test
    void invalidValuesAreRejectedAllOrNothing() {
        UUID admin = user("ADMIN");
        assertApi(() -> settings.update(admin, Map.of("withdrawal.min_amount", "abc")), "INVALID_SETTINGS");
        assertApi(() -> settings.update(admin, Map.of("nope.key", "1")), "INVALID_SETTINGS");
        assertApi(() -> settings.update(admin, Map.of("banner.variant", "purple")), "INVALID_SETTINGS");
        assertApi(() -> settings.update(admin, Map.of("banner.link_url", "javascript:alert(1)")), "INVALID_SETTINGS");
        assertApi(() -> settings.update(admin, Map.of("banner.link_url", "//evil.example")), "INVALID_SETTINGS");
        // Cross-field rules.
        assertApi(() -> settings.update(admin, Map.of("withdrawal.min_amount", "100000",
                "withdrawal.max_amount", "50000")), "INVALID_SETTINGS");
        assertApi(() -> settings.update(admin, Map.of("banner.enabled", "true")), "INVALID_SETTINGS");
        // One bad key keeps the good one out too.
        assertApi(() -> settings.update(admin, Map.of("site.name", "Shop", "withdrawal.max_amount", "-1")),
                "INVALID_SETTINGS");
        assertThat(settings.get(Setting.SITE_NAME)).isEqualTo("VieGG");

        settings.update(admin, Map.of("banner.enabled", "TRUE", "banner.message", "Hello",
                "banner.link_url", "/help"));
        assertThat(settings.getBoolean(Setting.BANNER_ENABLED)).isTrue();
    }

    @Test
    void withdrawalLimitsAreEnforced() {
        UUID admin = user("ADMIN");
        UUID owner = owner();
        var bank = new WalletService.BankAccount("VCB", "0123456789", "Owner");

        assertApi(() -> wallet.requestWithdrawal(owner, 9_999, bank, UUID.randomUUID().toString()),
                "WITHDRAWAL_BELOW_MINIMUM");
        settings.update(admin, Map.of("withdrawal.min_amount", "50000", "withdrawal.max_amount", "200000"));
        assertApi(() -> wallet.requestWithdrawal(owner, 49_000, bank, UUID.randomUUID().toString()),
                "WITHDRAWAL_BELOW_MINIMUM");
        assertApi(() -> wallet.requestWithdrawal(owner, 200_001, bank, UUID.randomUUID().toString()),
                "WITHDRAWAL_ABOVE_MAXIMUM");
        // Within limits: the balance check comes next.
        assertApi(() -> wallet.requestWithdrawal(owner, 100_000, bank, UUID.randomUUID().toString()),
                "INSUFFICIENT_AVAILABLE_BALANCE");

        settings.update(admin, Map.of("withdrawal.enabled", "false"));
        assertApi(() -> wallet.requestWithdrawal(owner, 100_000, bank, UUID.randomUUID().toString()),
                "WITHDRAWAL_DISABLED");
    }

    @Test
    void publicEndpointAndPermissions() throws Exception {
        mvc.perform(get("/api/v1/settings/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['withdrawal.min_amount']").value("10000"))
                .andExpect(jsonPath("$['banner.enabled']").value("false"));

        String customer = bearer(user());
        mvc.perform(get("/api/v1/admin/settings").header("Authorization", customer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"site.name\":\"X\"}}"))
                .andExpect(status().isForbidden());

        String finance = bearer(user("FINANCE"));
        mvc.perform(get("/api/v1/admin/settings").header("Authorization", finance)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", finance)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"site.name\":\"X\"}}"))
                .andExpect(status().isForbidden());

        String admin = bearer(user("ADMIN"));
        mvc.perform(put("/api/v1/admin/settings").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"site.name\":\"VieGG Pro\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'site.name')].value").value("VieGG Pro"));
        mvc.perform(get("/api/v1/settings/public")).andExpect(jsonPath("$['site.name']").value("VieGG Pro"));
    }
}
