package com.rentgg.gameaccount.application;

import com.rentgg.gameaccount.domain.GameAccount;
import com.rentgg.gameaccount.domain.SecurityState;

import java.time.Instant;
import java.util.UUID;

/**
 * Never carries the password. {@code accountKey}: the in-game identifier as the owner typed it (null for games
 * identified by login, or for old accounts that still have to fill it in).
 */
public record GameAccountView(UUID id, UUID ownerId, UUID gameId, String serverRegion, String loginHint,
                              SecurityState securityState, Instant securityStateChangedAt, Instant rotationDueAt,
                              boolean recoveryEmailOwned, boolean recoveryPhoneOwned, boolean twoFactorOwned,
                              Instant attestedAt, Instant recoveryVerifiedAt, Integer activeCredentialVersion,
                              String accountKey) {

    static GameAccountView of(GameAccount a, Integer activeVersion) {
        return new GameAccountView(a.getId(), a.getOwnerId(), a.getGameId(), a.getServerRegion(), a.getLoginHint(),
                a.getSecurityState(), a.getSecurityStateChangedAt(), a.getRotationDueAt(), a.isRecoveryEmailOwned(),
                a.isRecoveryPhoneOwned(), a.isTwoFactorOwned(), a.getAttestedAt(), a.getRecoveryVerifiedAt(),
                activeVersion, a.getAccountKeyDisplay());
    }

    public boolean recoveryVerified() {
        return recoveryVerifiedAt != null;
    }
}
