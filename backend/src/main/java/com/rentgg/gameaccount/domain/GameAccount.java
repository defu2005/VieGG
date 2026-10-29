package com.rentgg.gameaccount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Unit of schedule locking and password rotation (§5.3). */
@Entity
@Table(name = "game_accounts")
public class GameAccount {

    @Id
    private UUID id;
    private UUID ownerId;
    private UUID gameId;
    private String serverRegion;
    private String loginHint;
    private String loginFingerprint;
    private String accountKey;
    private String accountKeyDisplay;
    private String accountKeyScope = "";
    @Enumerated(EnumType.STRING)
    private SecurityState securityState;
    private Instant securityStateChangedAt;
    private Instant rotationDueAt;
    private boolean recoveryEmailOwned;
    private boolean recoveryPhoneOwned;
    private boolean twoFactorOwned;
    private Instant attestedAt;
    private UUID recoveryVerifiedBy;
    private Instant recoveryVerifiedAt;
    private Instant suspendedAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected GameAccount() {}

    public GameAccount(UUID ownerId, UUID gameId, String serverRegion, String loginHint, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.gameId = gameId;
        this.serverRegion = serverRegion;
        this.loginHint = loginHint;
        this.securityState = SecurityState.PENDING_VERIFICATION;
        this.securityStateChangedAt = now;
        this.recoveryEmailOwned = true;
        this.recoveryPhoneOwned = true;
        this.twoFactorOwned = true;
        this.attestedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transition(SecurityState next, Instant now) {
        if (next == SecurityState.SUSPENDED_SECURITY_REVIEW && securityState != next) {
            this.suspendedAt = now;
        }
        this.securityState = next;
        this.securityStateChangedAt = now;
        this.updatedAt = now;
        if (next != SecurityState.ROTATION_PENDING && next != SecurityState.ROTATION_OVERDUE) {
            this.rotationDueAt = null;
        }
    }

    public void startRotationWindow(Instant now, Instant dueAt) {
        transition(SecurityState.ROTATION_PENDING, now);
        this.rotationDueAt = dueAt;
    }

    public void markRecoveryVerified(UUID adminId, Instant now) {
        this.recoveryVerifiedBy = adminId;
        this.recoveryVerifiedAt = now;
        transition(SecurityState.AVAILABLE, now);
    }

    public void setLoginHint(String loginHint, Instant now) {
        this.loginHint = loginHint;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getGameId() { return gameId; }
    public String getServerRegion() { return serverRegion; }
    public String getLoginHint() { return loginHint; }
    public String getLoginFingerprint() { return loginFingerprint; }

    public void setLoginFingerprint(String loginFingerprint) {
        this.loginFingerprint = loginFingerprint;
    }

    public String getAccountKey() { return accountKey; }
    public String getAccountKeyDisplay() { return accountKeyDisplay; }
    public String getAccountKeyScope() { return accountKeyScope; }

    /** {@code key}: normalized identifier; {@code display}: what the owner typed (trimmed); {@code scope}: server or ''. */
    public void setAccountKey(String key, String display, String scope, Instant now) {
        this.accountKey = key;
        this.accountKeyDisplay = display;
        this.accountKeyScope = scope == null ? "" : scope;
        this.updatedAt = now;
    }
    public SecurityState getSecurityState() { return securityState; }
    public Instant getSecurityStateChangedAt() { return securityStateChangedAt; }
    public Instant getRotationDueAt() { return rotationDueAt; }
    public boolean isRecoveryEmailOwned() { return recoveryEmailOwned; }
    public boolean isRecoveryPhoneOwned() { return recoveryPhoneOwned; }
    public boolean isTwoFactorOwned() { return twoFactorOwned; }
    public Instant getAttestedAt() { return attestedAt; }
    public UUID getRecoveryVerifiedBy() { return recoveryVerifiedBy; }
    public Instant getRecoveryVerifiedAt() { return recoveryVerifiedAt; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
