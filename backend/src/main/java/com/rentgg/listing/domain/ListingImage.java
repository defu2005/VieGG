package com.rentgg.listing.domain;

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
    private byte[] data;
    private Instant createdAt;

    protected ListingImage() {}

    public ListingImage(UUID listingId, int position, String contentType, byte[] data, Instant now) {
        this.id = UUID.randomUUID();
        this.listingId = listingId;
        this.position = position;
        this.contentType = contentType;
        this.sizeBytes = data.length;
        this.data = data;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public int getPosition() { return position; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
