package com.rentgg.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.notification.domain.Notification;
import com.rentgg.notification.infrastructure.NotificationRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.DomainEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-app notifications driven by outbox events. Idempotent per (event, user). */
@Service
public class NotificationService {

    private static final List<String> USER_KEYS = List.of("renterId", "ownerId", "userId", "sellerId", "openedBy");

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("OrderCreated", "Đơn thuê đã được tạo"),
            Map.entry("OrderPaid", "Thanh toán thành công"),
            Map.entry("OrderAwaitingApproval", "Có đơn thuê cần bạn duyệt"),
            Map.entry("OrderConfirmed", "Đơn thuê đã được xác nhận"),
            Map.entry("OrderRejected", "Đơn thuê bị từ chối"),
            Map.entry("OrderExpired", "Đơn thuê hết hạn thanh toán"),
            Map.entry("OrderStarted", "Đơn thuê đã bắt đầu, thông tin đăng nhập đã sẵn sàng"),
            Map.entry("OrderEndingSoon", "Đơn thuê sắp hết giờ"),
            Map.entry("OrderEnded", "Đơn thuê đã kết thúc"),
            Map.entry("OrderExtended", "Đơn thuê đã được gia hạn"),
            Map.entry("OrderExtensionExpired", "Yêu cầu gia hạn đã hết hạn thanh toán"),
            Map.entry("OrderExtensionFailed", "Gia hạn không thành công, tiền đã được hoàn lại"),
            Map.entry("OrderCancelled", "Đơn thuê đã bị hủy"),
            Map.entry("CredentialViewed", "Người thuê đã xem thông tin đăng nhập"),
            Map.entry("HandoverConfirmed", "Người thuê đã đăng nhập thành công"),
            Map.entry("OtpRequested", "Người thuê cần mã OTP"),
            Map.entry("GameAccountStateChanged", "Trạng thái account thay đổi"),
            Map.entry("RotationOverdue", "Quá hạn đổi mật khẩu"),
            Map.entry("CredentialRotated", "Đã cập nhật mật khẩu mới"),
            Map.entry("DepositStateChanged", "Trạng thái tiền cọc thay đổi"),
            Map.entry("RefundCompleted", "Đã hoàn tiền vào ví"),
            Map.entry("WalletToppedUp", "Nạp tiền vào ví thành công"),
            Map.entry("RefundFailed", "Hoàn tiền thất bại"),
            Map.entry("DisputeOpened", "Có khiếu nại mới"),
            Map.entry("DisputeEvidenceAdded", "Khiếu nại có bằng chứng mới"),
            Map.entry("DisputeResolved", "Khiếu nại đã được phân xử"),
            Map.entry("SellerHoldReleased", "Doanh thu đã khả dụng"),
            Map.entry("WithdrawalRequested", "Yêu cầu rút tiền mới"),
            Map.entry("WithdrawalPaid", "Rút tiền thành công"),
            Map.entry("WithdrawalFailed", "Rút tiền thất bại"),
            Map.entry("ListingStatusChanged", "Trạng thái listing thay đổi"),
            Map.entry("ListingSubmittedForReview", "Có listing mới chờ duyệt"),
            Map.entry("GameAccountPendingVerification", "Có account game mới chờ xác minh"),
            Map.entry("GameTosStatusChanged", "Trạng thái game thay đổi"),
            Map.entry("ApprovalRequested", "Có yêu cầu phê duyệt mới"),
            Map.entry("ApprovalDecided", "Yêu cầu phê duyệt đã có kết quả"),
            Map.entry("UserStatusChanged", "Trạng thái tài khoản thay đổi"),
            Map.entry("SupportTicketOpened", "Có yêu cầu hỗ trợ mới"),
            Map.entry("SupportMessagePosted", "Tin nhắn mới trong yêu cầu hỗ trợ"),
            Map.entry("SupportParticipantAdded", "Bạn được mời vào một cuộc trò chuyện hỗ trợ"),
            Map.entry("SupportTicketStatusChanged", "Yêu cầu hỗ trợ đã được cập nhật"));

    /** Events that also fan out to a staff role. */
    private static final Map<String, String> STAFF_FANOUT = Map.of(
            "DisputeOpened", "SUPPORT",
            "WithdrawalRequested", "FINANCE",
            "RefundFailed", "FINANCE",
            "ApprovalRequested", "ADMIN",
            "ListingSubmittedForReview", "ADMIN",
            "GameAccountPendingVerification", "ADMIN",
            "SupportTicketOpened", "SUPPORT");

    private final NotificationRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StaffDirectory staffDirectory;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, JdbcTemplate jdbc, ObjectMapper mapper,
                               StaffDirectory staffDirectory, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.staffDirectory = staffDirectory;
        this.clock = clock;
    }

    @EventListener
    public void on(DomainEvent event) {
        String title = TITLES.get(event.eventType());
        if (title == null) {
            return;
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (String key : USER_KEYS) {
            UUID id = event.uuid(key);
            if (id != null) {
                recipients.add(id);
            }
        }
        // Events that name their recipients explicitly (help desk chat).
        if (event.payload().get("recipientIds") instanceof Collection<?> ids) {
            for (Object id : ids) {
                recipients.add(UUID.fromString(id.toString()));
            }
        }
        // Owner should not be told about their own credential view etc.; keep it simple: targeted events.
        if (event.is("CredentialViewed")) {
            recipients.remove(event.uuid("renterId"));
            if (!Boolean.TRUE.equals(event.payload().get("firstView"))) {
                return;
            }
        }
        if (event.is("OtpRequested")) {
            recipients.remove(event.uuid("renterId"));
        }
        if (event.is("DisputeEvidenceAdded")) {
            recipients.remove(event.uuid("addedBy"));
        }
        String role = STAFF_FANOUT.get(event.eventType());
        if (role != null) {
            recipients.addAll(staffDirectory.userIdsWithRole(role));
        }
        String data = toJson(event.payload());
        String subject = event.string("subject");
        String body = event.eventType().startsWith("Support") && subject != null ? subject : title;
        for (UUID userId : recipients) {
            jdbc.update("""
                    insert into notifications (id, user_id, event_id, type, title, body, data, created_at)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict do nothing""",
                    UUID.randomUUID(), userId, event.id(), event.eventType(), title, body, data,
                    Timestamp.from(clock.instant()));
        }
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, boolean unreadOnly, Pageable pageable) {
        return unreadOnly ? repository.findByUserIdAndReadAtIsNull(userId, pageable)
                : repository.findByUserId(userId, pageable);
    }

    @Transactional
    public Notification markRead(UUID userId, UUID notificationId) {
        Notification n = repository.findById(notificationId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Thông báo"));
        n.markRead(clock.instant());
        return n;
    }

    /** Marks every unread notification of the user as read; returns how many changed. */
    @Transactional
    public int markAllRead(UUID userId) {
        return jdbc.update("update notifications set read_at = ? where user_id = ? and read_at is null",
                Timestamp.from(clock.instant()), userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        Long n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and read_at is null",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
