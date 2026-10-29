package com.rentgg.helpdesk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only chat message. */
@Entity
@Table(name = "support_messages")
public class TicketMessage {

    @Id
    private UUID id;
    private UUID ticketId;
    private UUID senderId;
    @Enumerated(EnumType.STRING)
    private MessageKind kind;
    private String body;
    private UUID attachmentId;
    private Instant createdAt;

    protected TicketMessage() {}

    private TicketMessage(UUID ticketId, UUID senderId, MessageKind kind, String body, UUID attachmentId, Instant now) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.senderId = senderId;
        this.kind = kind;
        this.body = body;
        this.attachmentId = attachmentId;
        this.createdAt = now;
    }

    public static TicketMessage text(UUID ticketId, UUID senderId, String body, UUID attachmentId, Instant now) {
        return new TicketMessage(ticketId, senderId, MessageKind.TEXT, body, attachmentId, now);
    }

    public static TicketMessage system(UUID ticketId, String body, Instant now) {
        return new TicketMessage(ticketId, null, MessageKind.SYSTEM, body, null, now);
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public UUID getSenderId() { return senderId; }
    public MessageKind getKind() { return kind; }
    public String getBody() { return body; }
    public UUID getAttachmentId() { return attachmentId; }
    public Instant getCreatedAt() { return createdAt; }
}
