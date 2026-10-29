package com.rentgg.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Extra time bought for a running rental (UC-17, P1). While PENDING_PAYMENT the time is only reserved
 * (orders.extension_hold_until); it becomes part of the order when paid.
 */
@Entity
@Table(name = "order_extensions")
public class OrderExtension {

    public enum Status { PENDING_PAYMENT, PAID, EXPIRED, CANCELLED }

    /** Payment intents of an extension use this idempotency-key prefix, followed by the extension id. */
    public static final String PAYMENT_KEY_PREFIX = "extension:";

    @Id
    private UUID id;
    private UUID orderId;
    private UUID renterId;
    private int durationMinutes;
    private long rentAmount;
    private long platformFee;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant newScheduledEndAt;
    private Instant newBlockEndAt;
    private Instant paymentExpiresAt;
    private UUID paymentIntentId;
    private Instant paidAt;
    private String idempotencyKey;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected OrderExtension() {}

    public OrderExtension(UUID orderId, UUID renterId, int durationMinutes, long rentAmount, long platformFee,
                          Instant newScheduledEndAt, Instant newBlockEndAt, Instant paymentExpiresAt,
                          String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.renterId = renterId;
        this.durationMinutes = durationMinutes;
        this.rentAmount = rentAmount;
        this.platformFee = platformFee;
        this.status = Status.PENDING_PAYMENT;
        this.newScheduledEndAt = newScheduledEndAt;
        this.newBlockEndAt = newBlockEndAt;
        this.paymentExpiresAt = paymentExpiresAt;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String paymentKey() {
        return PAYMENT_KEY_PREFIX + id;
    }

    public void attachPaymentIntent(UUID intentId, Instant now) {
        this.paymentIntentId = intentId;
        this.updatedAt = now;
    }

    public void markPaid(Instant now) {
        this.status = Status.PAID;
        this.paidAt = now;
        this.updatedAt = now;
    }

    public void close(Status terminal, Instant now) {
        this.status = terminal;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == Status.PENDING_PAYMENT;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getRenterId() { return renterId; }
    public int getDurationMinutes() { return durationMinutes; }
    public long getRentAmount() { return rentAmount; }
    public long getPlatformFee() { return platformFee; }
    public Status getStatus() { return status; }
    public Instant getNewScheduledEndAt() { return newScheduledEndAt; }
    public Instant getNewBlockEndAt() { return newBlockEndAt; }
    public Instant getPaymentExpiresAt() { return paymentExpiresAt; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public Instant getPaidAt() { return paidAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
