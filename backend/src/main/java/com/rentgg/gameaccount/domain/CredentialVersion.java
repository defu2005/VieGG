package com.rentgg.gameaccount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Encrypted credentials (AES-256-GCM). RETIRED versions stay for disputes but are never readable via API. */
@Entity
@Table(name = "credential_versions")
public class CredentialVersion {

    @Id
    private UUID id;
    private UUID gameAccountId;
    private int version;
    private byte[] ciphertext;
    private byte[] iv;
    private String keyId;
    private String status;
    private Instant createdAt;
    private Instant retiredAt;

    protected CredentialVersion() {}

    public CredentialVersion(UUID id, UUID gameAccountId, int version, byte[] ciphertext, byte[] iv, String keyId,
                             Instant now) {
        this.id = id;
        this.gameAccountId = gameAccountId;
        this.version = version;
        this.ciphertext = ciphertext;
        this.iv = iv;
        this.keyId = keyId;
        this.status = "ACTIVE";
        this.createdAt = now;
    }

    public void retire(Instant now) {
        this.status = "RETIRED";
        this.retiredAt = now;
    }

    public UUID getId() { return id; }
    public UUID getGameAccountId() { return gameAccountId; }
    public int getVersion() { return version; }
    public byte[] getCiphertext() { return ciphertext; }
    public byte[] getIv() { return iv; }
    public String getKeyId() { return keyId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "CredentialVersion[id=" + id + ", version=" + version + ", status=" + status + "]";
    }
}
