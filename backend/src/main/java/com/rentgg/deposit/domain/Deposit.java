package com.rentgg.deposit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Deposit bound 1–1 to an order (R5): no shared balance, no reuse across orders. */
@Entity
@Table(name = "deposits")
public class Deposit {

    @Id
    private UUID id;
    private UUID orderId;
    private UUID renterId;
    private long amount;
    @Enumerated(EnumType.STRING)
    private DepositStatus status;
    private Instant holdUntil;
    private long deductedAmount;
    private long refundedAmount;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Deposit() {}

    public Deposit(UUID orderId, UUID renterId, long amount, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.renterId = renterId;
        this.amount = amount;
        this.status = DepositStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void moveTo(DepositStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public void setHoldUntil(Instant holdUntil) {
        this.holdUntil = holdUntil;
    }

    /** Records the requested refund; at terminal states deducted + refunded = amount (CHECK in DB). */
    public void addRefunded(long value) {
        this.refundedAmount += value;
    }

    public void addDeducted(long value) {
        this.deductedAmount += value;
    }

    public long remaining() {
        return amount - deductedAmount - refundedAmount;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getRenterId() { return renterId; }
    public long getAmount() { return amount; }
    public DepositStatus getStatus() { return status; }
    public Instant getHoldUntil() { return holdUntil; }
    public long getDeductedAmount() { return deductedAmount; }
    public long getRefundedAmount() { return refundedAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
