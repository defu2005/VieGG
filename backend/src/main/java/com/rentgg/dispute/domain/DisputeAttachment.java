package com.rentgg.dispute.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A photo or video uploaded as dispute evidence. Append-only. */
@Entity
@Table(name = "dispute_attachments")
public class DisputeAttachment {

    @Id
    private UUID id;
    private UUID disputeId;
    private UUID uploadedBy;
    private String contentType;
    private int sizeBytes;
    private byte[] data;
    private Instant createdAt;

    protected DisputeAttachment() {}

    public DisputeAttachment(UUID disputeId, UUID uploadedBy, String contentType, byte[] data, Instant now) {
        this.id = UUID.randomUUID();
        this.disputeId = disputeId;
        this.uploadedBy = uploadedBy;
        this.contentType = contentType;
        this.sizeBytes = data.length;
        this.data = data;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getDisputeId() { return disputeId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
