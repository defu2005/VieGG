package com.rentgg.listing.domain;

import com.rentgg.shared.media.MediaStore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An account screenshot attached to a listing. */
@Entity
@Table(name = "listing_images")
public class ListingImage {

    @Id
    private UUID id;
    private UUID listingId;
    private int position;
    private String contentType;
    private int sizeBytes;
    /** Bytes kept in the row; null once they live in object storage. */
    private byte[] data;
    private String storageKey;
    private Instant createdAt;

    protected ListingImage() {}

    public ListingImage(UUID listingId, int position, String contentType, MediaStore.Stored media, Instant now) {
        this.id = UUID.randomUUID();
        this.listingId = listingId;
        this.position = position;
        this.contentType = contentType;
        this.sizeBytes = media.sizeBytes();
        this.data = media.data();
        this.storageKey = media.storageKey();
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public int getPosition() { return position; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }
}
