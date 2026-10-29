package com.rentgg;

import com.rentgg.catalog.domain.AccountKeyRule;
import com.rentgg.catalog.domain.AccountKeyType;
import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-game account identity: the same in-game account can be on VieGG only once, whoever owns it. */
class GameAccountKeyIT extends IntegrationTest {

    private UUID keyGame(AccountKeyType type, boolean perServer) {
        return game(p -> p.withAccountKey(new AccountKeyRule(type, null, null, perServer)));
    }

    private UUID add(UUID owner, UUID gameId, String server, String key) {
        return gameAccounts.create(owner, gameId, server, "login_" + UUID.randomUUID(), "pw-123456", true, true, true,
                key).id();
    }

    @Test
    void numericUid_isUniqueAcrossOwners_andLookAlikesCollide() {
        UUID gameId = keyGame(AccountKeyType.NUMERIC_ID, false);
        UUID owner = owner();
        assertApi(() -> add(owner, gameId, "VN", null), "ACCOUNT_KEY_REQUIRED");
        assertApi(() -> add(owner, gameId, "VN", "12ab"), "ACCOUNT_KEY_INVALID");
        UUID acc = add(owner, gameId, "VN", "800123456");
        assertThat(gameAccounts.get(acc).accountKey()).isEqualTo("800123456");

        // Same owner, different login: still the same game account.
        assertApi(() -> add(owner, gameId, "VN", "800 123 456"), "GAME_ACCOUNT_DUPLICATE");
        // A second VieGG user (e.g. a banned owner's new account): refused, and Support gets a signal.
        UUID other = owner();
        assertApi(() -> add(other, gameId, "VN", "0800123456"), "GAME_ACCOUNT_DUPLICATE");
        assertApi(() -> add(other, gameId, "VN", "８００１２３４５６"), "GAME_ACCOUNT_DUPLICATE"); // full-width digits
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where user_id = ? and type = 'DUPLICATE_GAME_ACCOUNT'",
                Integer.class, other)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where user_id = ? and type = 'DUPLICATE_GAME_ACCOUNT'",
                Integer.class, owner)).isZero();

        // Still held after the first owner is suspended.
        users.changeStatus(user("ADMIN"), owner, com.rentgg.identity.domain.UserStatus.SUSPENDED, "TEST");
        assertApi(() -> add(other, gameId, "VN", "800123456"), "GAME_ACCOUNT_DUPLICATE");
        // Same UID in another game is another account.
        add(other, keyGame(AccountKeyType.NUMERIC_ID, false), "VN", "800123456");
    }

    @Test
    void nameAndUuid_normalization() {
        UUID names = keyGame(AccountKeyType.NAME, false);
        UUID owner = owner();
        add(owner, names, "VN", "Faker #KR1");
        assertApi(() -> add(owner(), names, "VN", "  faker#kr1​ "), "GAME_ACCOUNT_DUPLICATE");

        UUID uuids = keyGame(AccountKeyType.UUID, false);
        add(owner, uuids, "VN", "069A79F4-44E9-4726-A5BE-FCA90E38AAF5");
        assertApi(() -> add(owner(), uuids, "VN", "069a79f444e94726a5befca90e38aaf5"), "GAME_ACCOUNT_DUPLICATE");
        assertApi(() -> add(owner, uuids, "VN", "not-a-uuid"), "ACCOUNT_KEY_INVALID");
    }

    @Test
    void perServer_allowsSameIdOnOtherServers() {
        UUID gameId = keyGame(AccountKeyType.NUMERIC_ID, true);
        UUID owner = owner();
        assertApi(() -> add(owner, gameId, " ", "123"), "SERVER_REGION_REQUIRED");
        add(owner, gameId, "Asia", "123");
        add(owner, gameId, "Europe", "123");
        assertApi(() -> add(owner(), gameId, " asia ", "123"), "GAME_ACCOUNT_DUPLICATE");
    }

    @Test
    void loginGames_keepOldBehaviour_andRuleLocksOnceKeysExist() {
        UUID gameId = game();
        UUID owner = owner();
        UUID legacy = add(owner, gameId, "VN", null); // LOGIN game: no key needed
        assertThat(gameAccounts.get(legacy).accountKey()).isNull();
        UUID admin = user("ADMIN");
        gameAccounts.verifyRecovery(admin, legacy);
        UUID listing = listings.create(owner, legacy, new ListingService.Content("t", "d", Map.of(), 30_000, 200_000,
                false, EndMode.FORCE_AT_END)).id();

        // Admin switches the game to UID: allowed, no account has a key yet.
        GamePolicy base = GamePolicy.defaults(catalog.get(gameId).name(), 50_000, 2_000_000);
        catalog.update(admin, gameId, base.withAccountKey(new AccountKeyRule(AccountKeyType.NUMERIC_ID, "UID", null, false)));
        // The old account must fill in its UID before it can be listed.
        assertApi(() -> listings.submit(owner, listing), "ACCOUNT_KEY_REQUIRED");
        UUID other = add(owner(), gameId, "VN", "555");
        assertApi(() -> gameAccounts.setAccountKey(owner, legacy, "555"), "GAME_ACCOUNT_DUPLICATE");
        gameAccounts.setAccountKey(owner, legacy, "777");
        assertThat(listings.submit(owner, listing).status().name()).isEqualTo("PENDING_REVIEW");
        // Verified + key set: the owner cannot swap it any more.
        assertApi(() -> gameAccounts.setAccountKey(owner, legacy, "778"), "ACCOUNT_KEY_LOCKED");

        // Keys exist now: type/server scope are locked, label/example can still change.
        assertApi(() -> catalog.update(admin, gameId, base.withAccountKey(
                new AccountKeyRule(AccountKeyType.NAME, null, null, false))), "ACCOUNT_KEY_RULE_LOCKED");
        catalog.update(admin, gameId, base.withAccountKey(
                new AccountKeyRule(AccountKeyType.NUMERIC_ID, "Mã nhân vật", "123456", false)));
        assertThat(catalog.get(gameId).accountKeyLabel()).isEqualTo("Mã nhân vật");
        // A null rule in an update keeps the current one.
        catalog.update(admin, gameId, base);
        assertThat(catalog.get(gameId).accountKey().type()).isEqualTo(AccountKeyType.NUMERIC_ID);
        assertThat(other).isNotNull();
    }

    @Test
    void api_createWithKey() throws Exception {
        UUID gameId = keyGame(AccountKeyType.NUMERIC_ID, false);
        UUID owner = owner();
        String body = """
                {"gameId":"%s","serverRegion":"VN","username":"api_login","password":"pw-123456",
                 "recoveryEmailOwned":true,"recoveryPhoneOwned":true,"twoFactorOwned":true,"accountKey":"424242"}"""
                .formatted(gameId);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/game-accounts")
                        .header("Authorization", bearer(owner))
                        .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountKey")
                        .value("424242"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/games/" + gameId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountKey.type")
                        .value("NUMERIC_ID"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountKeyLabel")
                        .value("UID"));
    }
}
