package com.rentgg.helpdesk.domain;

import com.rentgg.shared.media.MediaStore;
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
    /** Bytes kept in the row; null once they live in object storage. */
    private byte[] data;
    private String storageKey;
    private Instant createdAt;

    protected TicketAttachment() {}

    public TicketAttachment(UUID ticketId, UUID uploadedBy, String contentType, MediaStore.Stored media,
                            Instant now) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.uploadedBy = uploadedBy;
        this.contentType = contentType;
        this.sizeBytes = media.sizeBytes();
        this.data = media.data();
        this.storageKey = media.storageKey();
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }
}
