package com.rentgg.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    public enum ActionType { GAME_ALLOW, ACCOUNT_SECURITY_RESTORE, SELLER_HOLD_EARLY_RELEASE, LEDGER_ADJUSTMENT }

    public enum Status { PENDING, APPROVED, REJECTED, EXECUTED }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private ActionType actionType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;
    private UUID requestedBy;
    private String reasonCode;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID approvedBy;
    private Instant decidedAt;
    private Instant executedAt;
    private Instant expiresAt;
    private String resultNote;
    private String idempotencyKey;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected ApprovalRequest() {}

    public ApprovalRequest(ActionType actionType, Map<String, Object> payload, UUID requestedBy, String reasonCode,
                           Instant expiresAt, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.actionType = actionType;
        this.payload = payload;
        this.requestedBy = requestedBy;
        this.reasonCode = reasonCode;
        this.status = Status.PENDING;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void approve(UUID approver, Instant now) {
        this.status = Status.APPROVED;
        this.approvedBy = approver;
        this.decidedAt = now;
        this.updatedAt = now;
    }

    public void executed(Instant now) {
        this.status = Status.EXECUTED;
        this.executedAt = now;
        this.updatedAt = now;
    }

    public void reject(UUID decider, String note, Instant now) {
        this.status = Status.REJECTED;
        this.approvedBy = decider;
        this.decidedAt = now;
        this.resultNote = note;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public ActionType getActionType() { return actionType; }
    public Map<String, Object> getPayload() { return payload; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getReasonCode() { return reasonCode; }
    public Status getStatus() { return status; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getResultNote() { return resultNote; }
    public Instant getCreatedAt() { return createdAt; }
}
