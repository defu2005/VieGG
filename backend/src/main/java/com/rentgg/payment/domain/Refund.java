package com.rentgg.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    private UUID id;
    private UUID orderId;
    private UUID paymentIntentId;
    private String kind;
    private long amount;
    private String status;
    private String providerRef;
    private String reason;
    private int attempts;
    private String lastError;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Refund() {}

    public Refund(UUID orderId, UUID paymentIntentId, String kind, long amount, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.paymentIntentId = paymentIntentId;
        this.kind = kind;
        this.amount = amount;
        this.reason = reason;
        this.status = PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void succeeded(String providerRef, Instant now) {
        this.status = SUCCEEDED;
        this.providerRef = providerRef;
        this.attempts++;
        this.updatedAt = now;
    }

    public void failed(String error, Instant now) {
        this.status = FAILED;
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getKind() { return kind; }
    public long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public int getAttempts() { return attempts; }
    public Instant getUpdatedAt() { return updatedAt; }
}
