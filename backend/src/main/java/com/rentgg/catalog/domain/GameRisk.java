package com.rentgg.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One risk of renting out a game's accounts, defined by staff (V17) or by an owner for one listing (V25). */
@Entity
@Table(name = "game_risks")
public class GameRisk {

    @Id
    private UUID id;
    private UUID gameId;
    /** Set for a risk the owner added to this listing only; null for the game's own risks. */
    private UUID listingId;
    @Enumerated(EnumType.STRING)
    private RiskSide side;
    private String name;
    private String description;
    /** Staff's free-text advice on the deposit (OWNER) or compensation (RENTER); null = none. */
    private String recommendation;
    private Integer complaintWindowMinutes;
    /** OWNER risks only: true = deductions add up with other risks; false = the renter pays only this one. */
    private boolean stackable = true;
    private int position;
    private Instant archivedAt;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected GameRisk() {}

    public GameRisk(UUID gameId, Instant now) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A risk the owner adds to one of their listings; the caller picks {@code id} so terms can point at it first. */
    public static GameRisk forListing(UUID id, UUID gameId, UUID listingId, Instant now) {
        GameRisk r = new GameRisk(gameId, now);
        r.id = id;
        r.listingId = listingId;
        return r;
    }

    public void apply(RiskSide side, String name, String description, String recommendation,
                      Integer complaintWindowMinutes, boolean stackable, int position, Instant now) {
        this.side = side;
        // Renter risks are refunds / compensations, never deposit shares: always "stackable".
        this.stackable = side != RiskSide.OWNER || stackable;
        this.name = name;
        this.description = description;
        this.recommendation = recommendation;
        this.complaintWindowMinutes = complaintWindowMinutes;
        this.position = position;
        this.archivedAt = null;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        if (archivedAt == null) {
            this.archivedAt = now;
            this.updatedAt = now;
        }
    }

    public boolean isActive() { return archivedAt == null; }
    public UUID getId() { return id; }
    public UUID getGameId() { return gameId; }
    public UUID getListingId() { return listingId; }
    public RiskSide getSide() { return side; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getRecommendation() { return recommendation; }
    public Integer getComplaintWindowMinutes() { return complaintWindowMinutes; }
    public int getPosition() { return position; }
    public boolean isStackable() { return stackable; }
}
