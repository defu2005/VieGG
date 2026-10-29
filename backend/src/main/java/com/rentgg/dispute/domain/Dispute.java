package com.rentgg.dispute.domain;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class Dispute {

    @Id
    private UUID id;
    private UUID orderId;
    private UUID openedBy;
    @Enumerated(EnumType.STRING)
    private DisputeType type;
    @Enumerated(EnumType.STRING)
    private DisputeStatus status;
    private String description;
    /** Entries: {url, note, addedBy, addedAt}. MVP evidence = user-provided https URLs (A7). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_urls", columnDefinition = "jsonb")
    private List<Map<String, Object>> evidence = new ArrayList<>();
    private Instant deadlineAt;
    private long depositDeduction;
    private long rentRefund;
    /** OWNER_RISK / RENTER_RISK: the risk of the order snapshot this dispute is about. */
    private UUID riskId;
    /** RENTER_RISK: amount moved from the owner's wallet to the renter's. */
    private long compensation;
    private UUID decidedBy;
    private Instant decidedAt;
    private String reasonCode;
    private String decisionNote;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Dispute() {}

    public Dispute(UUID orderId, UUID openedBy, DisputeType type, String description, Instant deadlineAt, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.openedBy = openedBy;
        this.type = type;
        this.status = DisputeStatus.OPEN;
        this.description = description;
        this.deadlineAt = deadlineAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Dispute(UUID orderId, UUID openedBy, DisputeType type, UUID riskId, String description, Instant deadlineAt,
                   Instant now) {
        this(orderId, openedBy, type, description, deadlineAt, now);
        this.riskId = riskId;
    }

    public void addEvidence(List<String> urls, String note, UUID addedBy, Instant now) {
        List<Map<String, Object>> next = new ArrayList<>(evidence);
        for (String url : urls) {
            next.add(Map.of("url", url, "note", note == null ? "" : note, "addedBy", addedBy.toString(),
                    "addedAt", now.toString()));
        }
        if (urls.isEmpty() && note != null) {
            next.add(Map.of("note", note, "addedBy", addedBy.toString(), "addedAt", now.toString()));
        }
        this.evidence = next;
        this.updatedAt = now;
    }

    /** Lists an uploaded photo/video in the evidence timeline; the bytes live in {@link DisputeAttachment}. */
    public void addAttachmentEvidence(DisputeAttachment a, String note) {
        List<Map<String, Object>> next = new ArrayList<>(evidence);
        next.add(Map.of("attachmentId", a.getId().toString(), "contentType", a.getContentType(),
                "sizeBytes", a.getSizeBytes(), "note", note == null ? "" : note,
                "addedBy", a.getUploadedBy().toString(), "addedAt", a.getCreatedAt().toString()));
        this.evidence = next;
        this.updatedAt = a.getCreatedAt();
    }

    public int evidenceUrlCount() {
        return (int) evidence.stream().filter(e -> e.containsKey("url")).count();
    }

    public void decide(DisputeStatus status, long depositDeduction, long rentRefund, long compensation, UUID decidedBy,
                       String reasonCode, String note, Instant now) {
        this.status = status;
        this.depositDeduction = depositDeduction;
        this.rentRefund = rentRefund;
        this.compensation = compensation;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.reasonCode = reasonCode;
        this.decisionNote = note;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getOpenedBy() { return openedBy; }
    public DisputeType getType() { return type; }
    public DisputeStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public List<Map<String, Object>> getEvidence() { return evidence; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public long getDepositDeduction() { return depositDeduction; }
    public long getRentRefund() { return rentRefund; }
    public UUID getRiskId() { return riskId; }
    public long getCompensation() { return compensation; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getReasonCode() { return reasonCode; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
}
