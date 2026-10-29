package com.rentgg.helpdesk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An image sent in a help desk chat. */
@Entity
@Table(name = "support_attachments")
public class TicketAttachment {

    @Id
    private UUID id;
    private UUID ticketId;
    private UUID uploadedBy;
    private String contentType;
    private int sizeBytes;
    private byte[] data;
    private Instant createdAt;

    protected TicketAttachment() {}

    public TicketAttachment(UUID ticketId, UUID uploadedBy, String contentType, byte[] data, Instant now) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.uploadedBy = uploadedBy;
        this.contentType = contentType;
        this.sizeBytes = data.length;
        this.data = data;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
