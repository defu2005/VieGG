package com.rentgg.helpdesk.application;

import com.rentgg.helpdesk.domain.MessageKind;
import com.rentgg.helpdesk.domain.ParticipantRole;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.helpdesk.domain.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models of the help desk. */
public final class TicketViews {

    private TicketViews() {}

    /**
     * @param myRole       the caller's role in the conversation; null for staff who have not joined yet
     * @param canManage    the caller may invite/remove people, assign and set any status (help desk staff)
     * @param orderParties renter and owner of the linked order (staff only, to invite them in one click)
     */
    public record TicketView(UUID id, String subject, TicketCategory category, TicketStatus status, UUID createdBy,
                             String createdByName, UUID orderId, UUID disputeId, UUID assigneeId, String assigneeName,
                             Instant lastMessageAt, String lastMessagePreview, Instant createdAt, Instant closedAt,
                             long unread, ParticipantRole myRole, boolean canManage,
                             List<ParticipantView> participants, OrderParties orderParties) {}

    public record ParticipantView(UUID userId, String displayName, ParticipantRole role, Instant joinedAt,
                                  boolean active) {}

    public record OrderParties(UUID renterId, String renterName, UUID ownerId, String ownerName) {}

    /** {@code senderRole}: null for SYSTEM messages. */
    public record MessageView(UUID id, UUID senderId, String senderName, ParticipantRole senderRole, MessageKind kind,
                              String body, UUID attachmentId, String attachmentType, Instant createdAt) {}

    /** {@code unreadTickets}: conversations with new messages · {@code awaitingStaff}: open tickets for staff. */
    public record UnreadSummary(long unreadTickets, long awaitingStaff) {}

    public record AttachmentData(String contentType, byte[] data) {}
}
