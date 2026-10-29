package com.rentgg.listing.domain;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "listings")
public class Listing {

    @Id
    private UUID id;
    private UUID gameAccountId;
    private UUID ownerId;
    private UUID gameId;
    private String title;
    private String description;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();
    private long pricePerHour;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> pricingTiers = new ArrayList<>();
    private long depositAmount;
    /** Deposit / compensation per game risk, see {@link RiskTerm}. Empty for listings made before risks existed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> riskTerms = new ArrayList<>();
    private boolean autoAccept;
    @Enumerated(EnumType.STRING)
    private EndMode endMode;
    /** Minutes before the start a renter can still cancel for a full refund; null = the game's default. */
    private Integer renterFreeCancelMinutes;
    @Enumerated(EnumType.STRING)
    private ListingStatus status;
    private String reviewNote;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Listing() {}

    public Listing(UUID gameAccountId, UUID ownerId, UUID gameId, Instant now) {
        this.id = UUID.randomUUID();
        this.gameAccountId = gameAccountId;
        this.ownerId = ownerId;
        this.gameId = gameId;
        this.status = ListingStatus.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Applies editable content. Returns true when a field relevant to renters (price, deposit, mode, text) changed. */
    /**
     * Applies the owner's edit and says what changed. Only renter-facing content (title, description, attributes)
     * needs review again; price, tiers, deposit, auto-accept and end mode are the owner's own terms and apply at once
     * (confirmed orders keep their policy_snapshot).
     */
    public ContentChange applyContent(String title, String description, Map<String, Object> attributes, long pricePerHour,
                                      List<PricingTier> tiers, long depositAmount, List<RiskTerm> riskTerms,
                                      boolean autoAccept, EndMode endMode, Instant now) {
        List<PricingTier> newTiers = PricingTier.sorted(tiers);
        List<RiskTerm> newTerms = RiskTerm.normalized(riskTerms);
        boolean needsReview = !Objects.equals(this.title, title) || !Objects.equals(this.description, description)
                || !Objects.equals(this.attributes == null ? Map.of() : this.attributes,
                                   attributes == null ? Map.of() : attributes);
        boolean termsChanged = this.pricePerHour != pricePerHour || !getPricingTiers().equals(newTiers)
                || this.depositAmount != depositAmount || this.endMode != endMode || this.autoAccept != autoAccept
                || !getRiskTerms().equals(newTerms);
        this.riskTerms = new ArrayList<>(RiskTerm.toJson(newTerms));
        ContentChange change = new ContentChange(needsReview, termsChanged, this.pricePerHour, this.depositAmount,
                this.autoAccept, this.endMode);
        this.title = title;
        this.description = description;
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        this.pricePerHour = pricePerHour;
        this.pricingTiers = new ArrayList<>(PricingTier.toJson(newTiers));
        this.depositAmount = depositAmount;
        this.autoAccept = autoAccept;
        this.endMode = endMode;
        this.updatedAt = now;
        return change;
    }

    /** What an edit touched; the old* fields are the values before the edit, kept for the audit trail. */
    public record ContentChange(boolean needsReview, boolean termsChanged, long oldPricePerHour, long oldDepositAmount,
                                boolean oldAutoAccept, EndMode oldEndMode) {}

    /** Sets the owner's free-cancel window (null = game default). Returns true when it changed. */
    public boolean applyRenterFreeCancelMinutes(Integer minutes, Instant now) {
        if (Objects.equals(this.renterFreeCancelMinutes, minutes)) {
            return false;
        }
        this.renterFreeCancelMinutes = minutes;
        this.updatedAt = now;
        return true;
    }

    /** Marks an image change; the update also bumps @Version so editors holding an older copy must reload. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void moveTo(ListingStatus status, String note, Instant now) {
        this.status = status;
        if (note != null) {
            this.reviewNote = note;
        }
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getGameAccountId() { return gameAccountId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getGameId() { return gameId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Map<String, Object> getAttributes() { return attributes; }
    public long getPricePerHour() { return pricePerHour; }
    /** Rental packages sorted by duration; empty for listings priced only per hour. */
    public List<PricingTier> getPricingTiers() { return PricingTier.sorted(PricingTier.fromJson(pricingTiers)); }
    public long getDepositAmount() { return depositAmount; }
    public List<RiskTerm> getRiskTerms() { return RiskTerm.normalized(RiskTerm.fromJson(riskTerms)); }
    public boolean isAutoAccept() { return autoAccept; }
    public EndMode getEndMode() { return endMode; }
    public Integer getRenterFreeCancelMinutes() { return renterFreeCancelMinutes; }
    public ListingStatus getStatus() { return status; }
    public String getReviewNote() { return reviewNote; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
