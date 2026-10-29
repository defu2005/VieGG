package com.rentgg.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "withdrawals")
public class Withdrawal {

    public static final String REQUESTED = "REQUESTED";
    public static final String PAID = "PAID";
    public static final String FAILED = "FAILED";

    @Id
    private UUID id;
    private UUID ownerId;
    private long amount;
    private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> bankSnapshot;
    private String idempotencyKey;
    private UUID processedBy;
    private Instant processedAt;
    private String bankRef;
    private String failureReason;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Withdrawal() {}

    public Withdrawal(UUID ownerId, long amount, Map<String, Object> bankSnapshot, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.amount = amount;
        this.status = REQUESTED;
        this.bankSnapshot = bankSnapshot;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markPaid(UUID by, String bankRef, Instant now) {
        this.status = PAID;
        this.processedBy = by;
        this.processedAt = now;
        this.bankRef = bankRef;
        this.updatedAt = now;
    }

    public void markFailed(UUID by, String reason, Instant now) {
        this.status = FAILED;
        this.processedBy = by;
        this.processedAt = now;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public long getAmount() { return amount; }
    public String getStatus() { return status; }
    public Map<String, Object> getBankSnapshot() { return bankSnapshot; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getProcessedBy() { return processedBy; }
    public Instant getProcessedAt() { return processedAt; }
    public String getBankRef() { return bankRef; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
