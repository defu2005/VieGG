package com.rentgg.helpdesk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_ticket_participants")
public class TicketParticipant {

    @Id
    private UUID id;
    private UUID ticketId;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private ParticipantRole role;
    private UUID addedBy;
    private Instant joinedAt;
    private Instant leftAt;
    private Instant lastReadAt;

    protected TicketParticipant() {}

    public TicketParticipant(UUID ticketId, UUID userId, ParticipantRole role, UUID addedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.ticketId = ticketId;
        this.userId = userId;
        this.role = role;
        this.addedBy = addedBy;
        this.joinedAt = now;
        this.lastReadAt = now;
    }

    public boolean active() {
        return leftAt == null;
    }

    public void leave(Instant now) {
        this.leftAt = now;
    }

    /** Re-invited after being removed: history before the new join stays hidden. */
    public void rejoin(UUID addedBy, Instant now) {
        this.leftAt = null;
        this.addedBy = addedBy;
        this.joinedAt = now;
        this.lastReadAt = now;
    }

    public void read(Instant at) {
        if (lastReadAt == null || at.isAfter(lastReadAt)) {
            this.lastReadAt = at;
        }
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public UUID getUserId() { return userId; }
    public ParticipantRole getRole() { return role; }
    public UUID getAddedBy() { return addedBy; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public Instant getLastReadAt() { return lastReadAt; }
}
