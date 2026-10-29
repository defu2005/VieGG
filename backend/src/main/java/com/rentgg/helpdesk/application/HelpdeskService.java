package com.rentgg.helpdesk.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeView;
import com.rentgg.dispute.domain.EvidenceMedia;
import com.rentgg.helpdesk.application.TicketViews.AttachmentData;
import com.rentgg.helpdesk.application.TicketViews.MessageView;
import com.rentgg.helpdesk.application.TicketViews.OrderParties;
import com.rentgg.helpdesk.application.TicketViews.ParticipantView;
import com.rentgg.helpdesk.application.TicketViews.TicketView;
import com.rentgg.helpdesk.application.TicketViews.UnreadSummary;
import com.rentgg.helpdesk.domain.ParticipantRole;
import com.rentgg.helpdesk.domain.SupportTicket;
import com.rentgg.helpdesk.domain.TicketAttachment;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.helpdesk.domain.TicketMessage;
import com.rentgg.helpdesk.domain.TicketParticipant;
import com.rentgg.helpdesk.domain.TicketStatus;
import com.rentgg.helpdesk.infrastructure.SupportTicketRepository;
import com.rentgg.helpdesk.infrastructure.TicketAttachmentRepository;
import com.rentgg.helpdesk.infrastructure.TicketMessageRepository;
import com.rentgg.helpdesk.infrastructure.TicketParticipantRepository;
import com.rentgg.identity.application.UserService;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.security.AuthorityResolver;
import com.rentgg.shared.security.Permission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Help desk chat: a user opens a ticket (question, support request, complaint) and chats with staff. Staff can
 * invite other users — typically the other side of an order — to mediate a dispute; an invited user only sees the
 * conversation from the moment they joined.
 */
@Service
public class HelpdeskService {

    public static final int MAX_BODY = 4000;
    public static final int MAX_SUBJECT = 200;
    static final int MAX_ATTACHMENTS_PER_TICKET = 30;
    static final int MAX_PARTICIPANTS = 10;

    private final SupportTicketRepository tickets;
    private final TicketParticipantRepository participants;
    private final TicketMessageRepository messages;
    private final TicketAttachmentRepository attachments;
    private final OrderService orders;
    private final DisputeService disputes;
    private final UserService users;
    private final AuthorityResolver authorities;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HelpdeskService(SupportTicketRepository tickets, TicketParticipantRepository participants,
                           TicketMessageRepository messages, TicketAttachmentRepository attachments,
                           OrderService orders, DisputeService disputes, UserService users,
                           AuthorityResolver authorities, AuditService audit, OutboxService outbox, JdbcTemplate jdbc,
                           Clock clock) {
        this.tickets = tickets;
        this.participants = participants;
        this.messages = messages;
        this.attachments = attachments;
        this.orders = orders;
        this.disputes = disputes;
        this.users = users;
        this.authorities = authorities;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ================================================================= open

    /** A user asks for help. {@code orderId} (optional) must be an order the user rents or owns. */
    @Transactional
    public TicketView open(UUID userId, String subject, TicketCategory category, UUID orderId, String body) {
        if (category == null || category == TicketCategory.MEDIATION) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Chọn loại yêu cầu: hỏi đáp, hỗ trợ hoặc khiếu nại.");
        }
        String s = requireSubject(subject);
        String b = requireBody(body);
        if (orderId != null) {
            OrderView o = orderOrNotFound(orderId);
            if (!o.renterId().equals(userId) && !o.ownerId().equals(userId)) {
                throw ApiException.notFound("Đơn thuê");
            }
        }
        Instant now = clock.instant();
        SupportTicket t = tickets.save(new SupportTicket(s, category, userId, orderId, null, now));
        participants.save(new TicketParticipant(t.getId(), userId, ParticipantRole.REQUESTER, userId, now));
        messages.save(TicketMessage.text(t.getId(), userId, b, null, now));
        t.messagePosted(b, now);
        outbox.record("SupportTicket", t.getId(), "SupportTicketOpened", Map.of("ticketId", t.getId(),
                "subject", s, "category", category, "createdBy", userId));
        return view(t, userId);
    }

    /**
     * Staff starts a conversation with one or more users, e.g. a mediation between renter and owner of a disputed
     * order. With {@code disputeId} the order is taken from the dispute.
     */
    @Transactional
    public TicketView openByStaff(UUID staffId, String subject, TicketCategory category, UUID orderId,
                                  UUID disputeId, Collection<UUID> userIds, String body) {
        requireStaff(staffId);
        String s = requireSubject(subject);
        String b = requireBody(body);
        if (disputeId != null) {
            DisputeView d = disputes.getForCaller(staffId, disputeId);
            if (orderId != null && !orderId.equals(d.orderId())) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Khiếu nại không thuộc đơn này.");
            }
            orderId = d.orderId();
        } else if (orderId != null) {
            orderOrNotFound(orderId);
        }
        Set<UUID> invited = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        if (invited.isEmpty() && orderId != null) {
            // No one picked: a mediation between both sides of the order.
            OrderView o = orders.getInternal(orderId);
            invited.add(o.renterId());
            invited.add(o.ownerId());
        }
        invited.remove(staffId);
        if (invited.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Chọn ít nhất một người dùng.");
        }
        if (invited.size() > MAX_PARTICIPANTS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tối đa " + MAX_PARTICIPANTS + " người.");
        }
        invited.forEach(users::get); // 404 for unknown users
        Instant now = clock.instant();
        SupportTicket t = tickets.save(new SupportTicket(s, category == null ? TicketCategory.MEDIATION : category,
                staffId, orderId, disputeId, now));
        participants.save(new TicketParticipant(t.getId(), staffId, ParticipantRole.STAFF, staffId, now));
        for (UUID u : invited) {
            participants.save(new TicketParticipant(t.getId(), u, ParticipantRole.PARTY, staffId, now));
        }
        t.assign(staffId, now);
        messages.save(TicketMessage.text(t.getId(), staffId, b, null, now));
        t.messagePosted(b, now);
        t.changeStatus(TicketStatus.WAITING_USER, now);
        audit.record(staffId, "HELPDESK_TICKET_OPENED", "SupportTicket", t.getId(), null,
                Map.of("userIds", invited.stream().map(UUID::toString).toList(),
                        "orderId", String.valueOf(orderId), "disputeId", String.valueOf(disputeId)), null);
        outbox.record("SupportTicket", t.getId(), "SupportParticipantAdded", Map.of("ticketId", t.getId(),
                "subject", s, "recipientIds", invited.stream().map(UUID::toString).toList()));
        return view(t, staffId);
    }

    // ================================================================= chat

    @Transactional
    public MessageView post(UUID callerId, UUID ticketId, String body, byte[] image) {
        SupportTicket t = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        boolean staff = isStaff(callerId);
        TicketParticipant me = activeParticipant(t.getId(), callerId);
        if (me == null && !staff) {
            throw ApiException.notFound("Yêu cầu hỗ trợ");
        }
        if (t.isClosed()) {
            throw ApiException.invalidState(staff ? "Yêu cầu đã đóng. Mở lại trước khi nhắn."
                    : "Yêu cầu đã đóng. Hãy tạo yêu cầu mới nếu cần hỗ trợ thêm.");
        }
        String b = body == null ? "" : body.trim();
        boolean hasImage = image != null && image.length > 0;
        if (b.isEmpty() && !hasImage) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Nhập nội dung tin nhắn.");
        }
        if (b.length() > MAX_BODY) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tin nhắn tối đa " + MAX_BODY + " ký tự.");
        }
        Instant now = clock.instant();
        // Taken before a staff auto-join adds its own system message.
        Instant previous = t.getLastMessageAt();
        if (staff && me == null) {
            me = joinAsStaff(t, callerId, now);
        }
        UUID attachmentId = null;
        String attachmentType = null;
        if (hasImage) {
            attachmentType = EvidenceMedia.detect(image);
            if (attachmentType == null || EvidenceMedia.isVideo(attachmentType)) {
                throw ApiException.unprocessable("UNSUPPORTED_MEDIA_TYPE", "Chỉ gửi được ảnh JPEG, PNG hoặc WebP.");
            }
            if (image.length > EvidenceMedia.MAX_IMAGE_BYTES) {
                throw ApiException.unprocessable("FILE_TOO_LARGE", "Ảnh tối đa 5 MB.");
            }
            if (attachments.countByTicket(t.getId()) >= MAX_ATTACHMENTS_PER_TICKET) {
                throw ApiException.unprocessable("TOO_MANY_ATTACHMENTS",
                        "Mỗi yêu cầu tối đa " + MAX_ATTACHMENTS_PER_TICKET + " ảnh.");
            }
            attachmentId = attachments.save(new TicketAttachment(t.getId(), callerId, attachmentType, image, now))
                    .getId();
        }
        // Who should be notified: only people who had read everything so far (no flood of one ping per message).
        List<UUID> recipients = new ArrayList<>();
        for (TicketParticipant p : participants.findByTicketIdOrderByJoinedAt(t.getId())) {
            if (p.active() && !p.getUserId().equals(callerId)
                    && (p.getLastReadAt() == null || !p.getLastReadAt().isBefore(previous))) {
                recipients.add(p.getUserId());
            }
        }
        TicketMessage m = messages.save(TicketMessage.text(t.getId(), callerId, b, attachmentId, now));
        t.messagePosted(b.isEmpty() ? "[Ảnh]" : b, now);
        me.read(now);
        // A user reply puts the ticket back in the staff queue; a staff reply waits for the user.
        if (me.getRole() == ParticipantRole.STAFF) {
            if (t.getStatus() == TicketStatus.OPEN) {
                t.changeStatus(TicketStatus.WAITING_USER, now);
            }
        } else if (t.getStatus() != TicketStatus.OPEN) {
            t.changeStatus(TicketStatus.OPEN, now);
        }
        if (!recipients.isEmpty()) {
            outbox.record("SupportTicket", t.getId(), "SupportMessagePosted", Map.of("ticketId", t.getId(),
                    "subject", t.getSubject(), "senderId", callerId,
                    "recipientIds", recipients.stream().map(UUID::toString).toList()));
        }
        Map<UUID, String> names = new HashMap<>();
        return messageView(m, Map.of(callerId, me.getRole()), names,
                attachmentId == null ? Map.of() : Map.of(attachmentId, attachmentType));
    }

    /** Messages the caller may see, oldest first; {@code after} returns only newer ones (polling). */
    @Transactional(readOnly = true)
    public List<MessageView> messages(UUID callerId, UUID ticketId, Instant after) {
        SupportTicket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        Instant from = visibleFrom(t, callerId);
        if (after != null && after.isAfter(from)) {
            from = after.plusNanos(1000);
        }
        List<TicketMessage> list = messages.findVisible(t.getId(), from);
        Map<UUID, ParticipantRole> roles = new HashMap<>();
        for (TicketParticipant p : participants.findByTicketIdOrderByJoinedAt(t.getId())) {
            roles.put(p.getUserId(), p.getRole());
        }
        Map<UUID, String> types = new HashMap<>();
        jdbc.query("select id, content_type from support_attachments where ticket_id = ?",
                rs -> { types.put(rs.getObject(1, UUID.class), rs.getString(2)); }, t.getId());
        Map<UUID, String> names = new HashMap<>();
        return list.stream().map(m -> messageView(m, roles, names, types)).toList();
    }

    @Transactional
    public void markRead(UUID callerId, UUID ticketId) {
        SupportTicket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        visibleFrom(t, callerId);
        TicketParticipant p = activeParticipant(t.getId(), callerId);
        if (p != null) {
            p.read(clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public AttachmentData attachment(UUID callerId, UUID ticketId, UUID attachmentId) {
        SupportTicket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        Instant from = visibleFrom(t, callerId);
        TicketAttachment a = attachments.findById(attachmentId)
                .filter(x -> x.getTicketId().equals(t.getId()))
                .orElseThrow(() -> ApiException.notFound("Tệp"));
        // Invited parties cannot open images sent before they joined.
        if (a.getCreatedAt().isBefore(from)) {
            throw ApiException.notFound("Tệp");
        }
        return new AttachmentData(a.getContentType(), a.getData());
    }

    // ================================================================= read

    @Transactional(readOnly = true)
    public TicketView get(UUID callerId, UUID ticketId) {
        SupportTicket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        visibleFrom(t, callerId);
        return view(t, callerId);
    }

    @Transactional(readOnly = true)
    public Page<TicketView> mine(UUID userId, Pageable pageable) {
        return tickets.findForParticipant(userId, pageable).map(t -> view(t, userId));
    }

    /** Staff queue. {@code statuses} empty = all but CLOSED. */
    @Transactional(readOnly = true)
    public Page<TicketView> queue(UUID staffId, Set<TicketStatus> statuses, TicketCategory category, boolean mine,
                                  boolean unassigned, String q, Pageable pageable) {
        requireStaff(staffId);
        Set<TicketStatus> st = statuses == null || statuses.isEmpty()
                ? EnumSet.complementOf(EnumSet.of(TicketStatus.CLOSED)) : statuses;
        String query = q == null ? "" : q.trim().toLowerCase();
        return tickets.searchQueue(st, category, mine, staffId, unassigned, query, pageable)
                .map(t -> view(t, staffId));
    }

    /** Tickets about one order the caller may see (renter, owner or staff). */
    @Transactional(readOnly = true)
    public List<TicketView> forOrder(UUID callerId, UUID orderId) {
        boolean staff = isStaff(callerId);
        return tickets.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(t -> staff || activeParticipant(t.getId(), callerId) != null)
                .map(t -> view(t, callerId)).toList();
    }

    @Transactional(readOnly = true)
    public UnreadSummary unread(UUID userId) {
        Long n = jdbc.queryForObject("""
                select count(*) from support_ticket_participants p
                 where p.user_id = ? and p.left_at is null
                   and exists (select 1 from support_messages m
                                where m.ticket_id = p.ticket_id
                                  and m.created_at > coalesce(p.last_read_at, p.joined_at)
                                  and m.created_at >= p.joined_at
                                  and (m.sender_id is null or m.sender_id <> p.user_id))""", Long.class, userId);
        long awaiting = isStaff(userId) ? tickets.countAwaitingStaff(userId) : 0;
        return new UnreadSummary(n == null ? 0 : n, awaiting);
    }

    // ================================================================= manage

    /** Staff invites a user into the conversation (e.g. the other side of the order). */
    @Transactional
    public TicketView addParticipant(UUID staffId, UUID ticketId, UUID userId) {
        requireStaff(staffId);
        SupportTicket t = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        if (t.isClosed()) {
            throw ApiException.invalidState("Yêu cầu đã đóng.");
        }
        String name = users.get(userId).displayName();
        Instant now = clock.instant();
        joinAsStaff(t, staffId, now);
        TicketParticipant p = participants.findByTicketIdAndUserId(t.getId(), userId).orElse(null);
        if (p != null && p.active()) {
            throw ApiException.conflict("ALREADY_PARTICIPANT", name + " đã ở trong cuộc trò chuyện.");
        }
        long active = participants.findByTicketIdOrderByJoinedAt(t.getId()).stream()
                .filter(TicketParticipant::active).count();
        if (active >= MAX_PARTICIPANTS) {
            throw ApiException.unprocessable("TOO_MANY_PARTICIPANTS", "Tối đa " + MAX_PARTICIPANTS + " người.");
        }
        ParticipantRole role = isStaff(userId) ? ParticipantRole.STAFF : ParticipantRole.PARTY;
        if (p == null) {
            participants.save(new TicketParticipant(t.getId(), userId, role, staffId, now));
        } else {
            p.rejoin(staffId, now);
        }
        system(t, name + " đã được mời vào cuộc trò chuyện.", now);
        audit.record(staffId, "HELPDESK_PARTICIPANT_ADDED", "SupportTicket", t.getId(), null,
                Map.of("userId", userId.toString()), null);
        outbox.record("SupportTicket", t.getId(), "SupportParticipantAdded", Map.of("ticketId", t.getId(),
                "subject", t.getSubject(), "recipientIds", List.of(userId.toString())));
        return view(t, staffId);
    }

    @Transactional
    public TicketView removeParticipant(UUID staffId, UUID ticketId, UUID userId) {
        requireStaff(staffId);
        SupportTicket t = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        TicketParticipant p = participants.findByTicketIdAndUserId(t.getId(), userId)
                .filter(TicketParticipant::active)
                .orElseThrow(() -> ApiException.notFound("Người tham gia"));
        if (p.getRole() == ParticipantRole.REQUESTER) {
            throw ApiException.unprocessable("CANNOT_REMOVE_REQUESTER", "Không thể mời người mở yêu cầu ra ngoài.");
        }
        Instant now = clock.instant();
        p.leave(now);
        system(t, users.get(userId).displayName() + " đã rời cuộc trò chuyện.", now);
        audit.record(staffId, "HELPDESK_PARTICIPANT_REMOVED", "SupportTicket", t.getId(), null,
                Map.of("userId", userId.toString()), null);
        return view(t, staffId);
    }

    /** Assigns the ticket to a staff member ({@code assigneeId} null = unassign). */
    @Transactional
    public TicketView assign(UUID staffId, UUID ticketId, UUID assigneeId) {
        requireStaff(staffId);
        SupportTicket t = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        Instant now = clock.instant();
        if (assigneeId != null) {
            if (!isStaff(assigneeId)) {
                throw ApiException.unprocessable("NOT_STAFF", "Chỉ giao được cho hỗ trợ viên.");
            }
            joinAsStaff(t, assigneeId, now);
            system(t, users.get(assigneeId).displayName() + " phụ trách yêu cầu này.", now);
        }
        t.assign(assigneeId, now);
        audit.record(staffId, "HELPDESK_ASSIGNED", "SupportTicket", t.getId(), null,
                Map.of("assigneeId", String.valueOf(assigneeId)), null);
        return view(t, staffId);
    }

    /**
     * Staff may set any status. A participant may mark the ticket resolved, or reopen a resolved one; CLOSED is
     * final for users.
     */
    @Transactional
    public TicketView changeStatus(UUID callerId, UUID ticketId, TicketStatus next, String note) {
        if (next == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Thiếu trạng thái.");
        }
        SupportTicket t = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiException.notFound("Yêu cầu hỗ trợ"));
        boolean staff = isStaff(callerId);
        TicketParticipant me = activeParticipant(t.getId(), callerId);
        if (!staff) {
            if (me == null) {
                throw ApiException.notFound("Yêu cầu hỗ trợ");
            }
            boolean allowed = (next == TicketStatus.RESOLVED && t.getStatus() != TicketStatus.CLOSED)
                    || (next == TicketStatus.OPEN && t.getStatus() == TicketStatus.RESOLVED);
            if (!allowed) {
                throw ApiException.forbidden("FORBIDDEN", "Bạn không thể đổi sang trạng thái này.");
            }
        }
        if (t.getStatus() == next) {
            return view(t, callerId);
        }
        Instant now = clock.instant();
        String who = users.get(callerId).displayName();
        String text = switch (next) {
            case OPEN -> who + " đã mở lại yêu cầu.";
            case WAITING_USER -> who + " đang chờ phản hồi từ người dùng.";
            case RESOLVED -> who + " đã đánh dấu yêu cầu là đã giải quyết.";
            case CLOSED -> who + " đã đóng yêu cầu.";
        };
        if (note != null && !note.isBlank()) {
            text += " Ghi chú: " + note.trim();
        }
        t.changeStatus(next, now);
        system(t, text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text, now);
        if (staff) {
            audit.record(callerId, "HELPDESK_STATUS_CHANGED", "SupportTicket", t.getId(), null,
                    Map.of("status", next.name()), null);
        }
        List<String> recipients = participants.findByTicketIdOrderByJoinedAt(t.getId()).stream()
                .filter(p -> p.active() && !p.getUserId().equals(callerId))
                .map(p -> p.getUserId().toString()).toList();
        if (!recipients.isEmpty() && (next == TicketStatus.RESOLVED || next == TicketStatus.CLOSED)) {
            outbox.record("SupportTicket", t.getId(), "SupportTicketStatusChanged", Map.of("ticketId", t.getId(),
                    "subject", t.getSubject(), "status", next, "recipientIds", recipients));
        }
        return view(t, callerId);
    }

    // ================================================================= helpers

    private boolean isStaff(UUID userId) {
        return authorities.resolve(userId).permissions().contains(Permission.HELPDESK_HANDLE.code());
    }

    private void requireStaff(UUID userId) {
        if (!isStaff(userId)) {
            throw ApiException.forbidden("FORBIDDEN", "Bạn không có quyền xử lý yêu cầu hỗ trợ.");
        }
    }

    private TicketParticipant activeParticipant(UUID ticketId, UUID userId) {
        return participants.findByTicketIdAndUserId(ticketId, userId).filter(TicketParticipant::active).orElse(null);
    }

    /** Earliest message the caller may read; 404 when they may not see the ticket at all. */
    private Instant visibleFrom(SupportTicket t, UUID callerId) {
        TicketParticipant p = activeParticipant(t.getId(), callerId);
        if (p != null) {
            return p.getRole() == ParticipantRole.PARTY ? p.getJoinedAt() : Instant.EPOCH;
        }
        if (isStaff(callerId)) {
            return Instant.EPOCH;
        }
        throw ApiException.notFound("Yêu cầu hỗ trợ");
    }

    private TicketParticipant joinAsStaff(SupportTicket t, UUID staffId, Instant now) {
        TicketParticipant p = participants.findByTicketIdAndUserId(t.getId(), staffId).orElse(null);
        if (p != null && p.active()) {
            return p;
        }
        if (p == null) {
            p = participants.save(new TicketParticipant(t.getId(), staffId, ParticipantRole.STAFF, staffId, now));
        } else {
            p.rejoin(staffId, now);
        }
        if (t.getAssigneeId() == null) {
            t.assign(staffId, now);
        }
        system(t, "Hỗ trợ viên " + users.get(staffId).displayName() + " đã tham gia.", now);
        return p;
    }

    private void system(SupportTicket t, String text, Instant now) {
        messages.save(TicketMessage.system(t.getId(), text, now));
        t.messagePosted(text, now);
    }

    private OrderView orderOrNotFound(UUID orderId) {
        try {
            return orders.getInternal(orderId);
        } catch (ApiException e) {
            throw ApiException.notFound("Đơn thuê");
        }
    }

    private static String requireSubject(String subject) {
        String s = subject == null ? "" : subject.trim();
        if (s.isEmpty() || s.length() > MAX_SUBJECT) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tiêu đề từ 1 đến " + MAX_SUBJECT + " ký tự.");
        }
        return s;
    }

    private static String requireBody(String body) {
        String b = body == null ? "" : body.trim();
        if (b.isEmpty() || b.length() > MAX_BODY) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Nội dung từ 1 đến " + MAX_BODY + " ký tự.");
        }
        return b;
    }

    private String name(UUID userId, Map<UUID, String> cache) {
        if (userId == null) {
            return null;
        }
        return cache.computeIfAbsent(userId, id -> {
            try {
                return users.get(id).displayName();
            } catch (ApiException e) {
                return "Người dùng";
            }
        });
    }

    private MessageView messageView(TicketMessage m, Map<UUID, ParticipantRole> roles, Map<UUID, String> names,
                                    Map<UUID, String> attachmentTypes) {
        return new MessageView(m.getId(), m.getSenderId(), name(m.getSenderId(), names),
                m.getSenderId() == null ? null : roles.get(m.getSenderId()), m.getKind(), m.getBody(),
                m.getAttachmentId(), m.getAttachmentId() == null ? null : attachmentTypes.get(m.getAttachmentId()),
                m.getCreatedAt());
    }

    private TicketView view(SupportTicket t, UUID callerId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        List<TicketParticipant> ps = participants.findByTicketIdOrderByJoinedAt(t.getId());
        TicketParticipant me = ps.stream().filter(p -> p.getUserId().equals(callerId) && p.active())
                .findFirst().orElse(null);
        boolean staff = isStaff(callerId);
        long unread = 0;
        if (me != null) {
            Instant since = me.getLastReadAt() == null ? me.getJoinedAt() : me.getLastReadAt();
            Long n = jdbc.queryForObject("""
                    select count(*) from support_messages
                     where ticket_id = ? and created_at > ? and created_at >= ?
                       and (sender_id is null or sender_id <> ?)""", Long.class,
                    t.getId(), Timestamp.from(since), Timestamp.from(me.getJoinedAt()), callerId);
            unread = n == null ? 0 : n;
        }
        List<ParticipantView> pv = ps.stream()
                .map(p -> new ParticipantView(p.getUserId(), name(p.getUserId(), names), p.getRole(),
                        p.getJoinedAt(), p.active()))
                .toList();
        OrderParties parties = null;
        if (staff && t.getOrderId() != null) {
            OrderView o = orders.getInternal(t.getOrderId());
            parties = new OrderParties(o.renterId(), name(o.renterId(), names), o.ownerId(),
                    name(o.ownerId(), names));
        }
        return new TicketView(t.getId(), t.getSubject(), t.getCategory(), t.getStatus(), t.getCreatedBy(),
                name(t.getCreatedBy(), names), t.getOrderId(), t.getDisputeId(), t.getAssigneeId(),
                name(t.getAssigneeId(), names), t.getLastMessageAt(), t.getLastMessagePreview(), t.getCreatedAt(),
                t.getClosedAt(), unread, me == null ? null : me.getRole(), staff, pv, parties);
    }
}
