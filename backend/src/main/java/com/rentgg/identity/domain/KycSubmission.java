package com.rentgg.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "kyc_submissions")
public class KycSubmission {

    @Id
    private UUID id;
    private UUID userId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;
    private String status;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;

    protected KycSubmission() {}

    public KycSubmission(UUID userId, Map<String, Object> payload, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.payload = payload;
        this.status = "PENDING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getStatus() { return status; }

    public void decide(String status, UUID reviewer, String note, Instant now) {
        this.status = status;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
        this.note = note;
        this.updatedAt = now;
    }
}
