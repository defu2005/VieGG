package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rotation never goes back to an old password, and the suggested password is strong and unused. */
class GameAccountPasswordIT extends IntegrationTest {

    @Test
    void rotation_refusesAnyPasswordTheAccountHasUsed() {
        UUID gameId = game();
        UUID owner = owner();
        UUID acc = gameAccounts.create(owner, gameId, "VN", "pw_login", "first-pass", true, true, true).id();
        gameAccounts.verifyRecovery(user("ADMIN"), acc);

        assertApi(() -> gameAccounts.rotate(owner, acc, null, "first-pass", true), "PASSWORD_REUSED");
        gameAccounts.rotate(owner, acc, null, "second-pass", true);
        assertApi(() -> gameAccounts.rotate(owner, acc, null, "second-pass", true), "PASSWORD_REUSED");
        // Retired versions count too.
        assertApi(() -> gameAccounts.rotate(owner, acc, null, "first-pass", true), "PASSWORD_REUSED");
        assertThat(gameAccounts.rotate(owner, acc, null, "third-pass", true).activeCredentialVersion()).isEqualTo(3);
    }

    @Test
    void suggestion_isStrong_unused_andOwnerOnly() {
        UUID gameId = game();
        UUID owner = owner();
        UUID acc = gameAccounts.create(owner, gameId, "VN", "sg_login", "first-pass", true, true, true).id();
        gameAccounts.verifyRecovery(user("ADMIN"), acc);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String p = gameAccounts.suggestPassword(owner, acc);
            assertThat(p).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[0-9].*")
                    .matches(".*[@#$%&*!?].*").doesNotContain("0", "O", "1", "l", "I");
            seen.add(p);
        }
        assertThat(seen).hasSize(20);

        String suggested = gameAccounts.suggestPassword(owner, acc);
        gameAccounts.rotate(owner, acc, null, suggested, true);
        assertThat(gameAccounts.decryptActive(acc).password()).isEqualTo(suggested);
        assertApi(() -> gameAccounts.rotate(owner, acc, null, suggested, true), "PASSWORD_REUSED");

        assertApi(() -> gameAccounts.suggestPassword(owner(), acc), "NOT_FOUND");
    }
}
