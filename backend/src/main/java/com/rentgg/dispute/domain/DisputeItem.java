package com.rentgg.dispute.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One risk listed in a renter complaint (RENTER_RISK), decided by support on its own (see V24). */
@Entity
@Table(name = "dispute_items")
public class DisputeItem {

    public enum Status { OPEN, ACCEPTED, REJECTED }

    @Id
    private UUID id;
    private UUID disputeId;
    private UUID orderId;
    private UUID riskId;
    private int position;
    @Enumerated(EnumType.STRING)
    private Status status;
    /** ACCEPTED: the owner's compensation for this risk, taken from the order snapshot. */
    private long compensation;
    private String reasonCode;
    private String note;
    private UUID decidedBy;
    private Instant decidedAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected DisputeItem() {}

    public DisputeItem(UUID disputeId, UUID orderId, UUID riskId, int position, Instant now) {
        this.id = UUID.randomUUID();
        this.disputeId = disputeId;
        this.orderId = orderId;
        this.riskId = riskId;
        this.position = position;
        this.status = Status.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void decide(boolean accept, long compensation, UUID decidedBy, String reasonCode, String note, Instant now) {
        this.status = accept ? Status.ACCEPTED : Status.REJECTED;
        this.compensation = accept ? compensation : 0;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.reasonCode = reasonCode;
        this.note = note;
        this.updatedAt = now;
    }

    /** Closes an item left open when the whole complaint is decided at once. */
    public void closeWithDispute(boolean accepted, UUID decidedBy, String reasonCode, Instant now) {
        if (status == Status.OPEN) {
            decide(accepted, 0, decidedBy, reasonCode, null, now);
        }
    }

    public UUID getId() { return id; }
    public UUID getDisputeId() { return disputeId; }
    public UUID getOrderId() { return orderId; }
    public UUID getRiskId() { return riskId; }
    public int getPosition() { return position; }
    public Status getStatus() { return status; }
    public long getCompensation() { return compensation; }
    public String getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
