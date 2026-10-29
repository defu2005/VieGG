package com.rentgg.helpdesk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    private static final int PREVIEW = 140;

    @Id
    private UUID id;
    private String subject;
    @Enumerated(EnumType.STRING)
    private TicketCategory category;
    @Enumerated(EnumType.STRING)
    private TicketStatus status;
    private UUID createdBy;
    private UUID orderId;
    private UUID disputeId;
    private UUID assigneeId;
    private Instant lastMessageAt;
    private String lastMessagePreview;
    private Instant closedAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected SupportTicket() {}

    public SupportTicket(String subject, TicketCategory category, UUID createdBy, UUID orderId, UUID disputeId,
                         Instant now) {
        this.id = UUID.randomUUID();
        this.subject = subject;
        this.category = category;
        this.status = TicketStatus.OPEN;
        this.createdBy = createdBy;
        this.orderId = orderId;
        this.disputeId = disputeId;
        this.lastMessageAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void messagePosted(String body, Instant now) {
        String flat = body.replaceAll("\\s+", " ").trim();
        this.lastMessagePreview = flat.length() > PREVIEW ? flat.substring(0, PREVIEW - 1) + "…" : flat;
        this.lastMessageAt = now;
        this.updatedAt = now;
    }

    public void changeStatus(TicketStatus next, Instant now) {
        this.status = next;
        this.closedAt = next == TicketStatus.CLOSED || next == TicketStatus.RESOLVED ? now : null;
        this.updatedAt = now;
    }

    public void assign(UUID staffId, Instant now) {
        this.assigneeId = staffId;
        this.updatedAt = now;
    }

    public boolean isClosed() {
        return status == TicketStatus.CLOSED;
    }

    public UUID getId() { return id; }
    public String getSubject() { return subject; }
    public TicketCategory getCategory() { return category; }
    public TicketStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getOrderId() { return orderId; }
    public UUID getDisputeId() { return disputeId; }
    public UUID getAssigneeId() { return assigneeId; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public Instant getClosedAt() { return closedAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
