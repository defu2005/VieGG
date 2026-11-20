package com.rentgg.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;
    private Instant createdAt;
    private boolean mfaVerified;

    protected RefreshToken() {}

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt, Instant now) {
        this(userId, tokenHash, expiresAt, now, false);
    }

    /** {@code mfaVerified}: the session passed the second factor; carried over on refresh. */
    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt, Instant now, boolean mfaVerified) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.mfaVerified = mfaVerified;
    }

    public boolean isMfaVerified() { return mfaVerified; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public void revoke(Instant now, UUID replacedBy) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.replacedBy = replacedBy;
        }
    }
}
