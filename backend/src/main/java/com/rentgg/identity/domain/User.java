package com.rentgg.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;
    private String email;
    private String passwordHash;
    private String displayName;
    private LocalDate dateOfBirth;
    @Enumerated(EnumType.STRING)
    private UserStatus status;
    @Enumerated(EnumType.STRING)
    private KycStatus kycStatus;
    @Enumerated(EnumType.STRING)
    private RiskTier riskTier;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    private Set<String> roles = new HashSet<>();

    protected User() {}

    public User(String email, String passwordHash, String displayName, LocalDate dateOfBirth, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email.toLowerCase();
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.dateOfBirth = dateOfBirth;
        this.status = UserStatus.ACTIVE;
        this.kycStatus = KycStatus.NONE;
        this.riskTier = RiskTier.STANDARD;
        this.createdAt = now;
        this.updatedAt = now;
        this.roles.add("USER");
    }

    public int ageOn(LocalDate date) {
        return Period.between(dateOfBirth, date).getYears();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public UserStatus getStatus() { return status; }
    public KycStatus getKycStatus() { return kycStatus; }
    public RiskTier getRiskTier() { return riskTier; }
    public Set<String> getRoles() { return roles; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(UserStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public void setKycStatus(KycStatus kycStatus, Instant now) {
        this.kycStatus = kycStatus;
        this.updatedAt = now;
    }

    public void setRiskTier(RiskTier riskTier, Instant now) {
        this.riskTier = riskTier;
        this.updatedAt = now;
    }

    public void setDisplayName(String displayName, Instant now) {
        this.displayName = displayName;
        this.updatedAt = now;
    }

    public void setPasswordHash(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.updatedAt = now;
    }

    public void addRole(String role) {
        roles.add(role);
    }

    public void removeRole(String role) {
        roles.remove(role);
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }
}
