package com.rentgg.notification.application;

import java.util.Map;
import java.util.Optional;

/**
 * Title and body of each in-app notification, per domain event (§3 mục 16). A body that is not a copy of the title
 * tells the user what happened and what to do next. Support events use the message subject as their body instead.
 */
public final class NotificationTemplates {

    public record Template(String title, String body) {}

    private static final Map<String, Template> BY_EVENT = Map.ofEntries(
            Map.entry("OrderCreated", new Template("Đơn thuê đã được tạo",
                    "Đơn thuê đã được tạo. Xem đơn để biết trạng thái và các bước tiếp theo.")),
            Map.entry("OrderPaid", new Template("Thanh toán thành công",
                    "Đơn đã được thanh toán. Xem đơn để biết thời gian bắt đầu thuê.")),
            Map.entry("OrderAwaitingApproval", new Template("Có đơn thuê cần bạn duyệt",
                    "Có đơn thuê đang chờ chủ listing duyệt. Xem đơn để biết thời hạn.")),
            Map.entry("OrderConfirmed", new Template("Đơn thuê đã được xác nhận",
                    "Đơn thuê đã được xác nhận. Xem đơn để biết thời gian bắt đầu thuê.")),
            Map.entry("OrderRejected", new Template("Đơn thuê bị từ chối",
                    "Đơn thuê đã bị từ chối. Xem đơn để biết thông tin hoàn tiền.")),
            Map.entry("OrderExpired", new Template("Đơn thuê hết hạn thanh toán",
                    "Đơn thuê đã hết hạn vì chưa thanh toán đúng thời hạn.")),
            Map.entry("OrderStarted", new Template("Đơn thuê đã bắt đầu, thông tin đăng nhập đã sẵn sàng",
                    "Đơn thuê đã bắt đầu. Xem đơn để biết các bước bàn giao.")),
            Map.entry("OrderEndingSoon", new Template("Đơn thuê sắp hết giờ",
                    "Đơn thuê sắp kết thúc. Xem đơn để biết thời gian còn lại.")),
            Map.entry("OrderEnded", new Template("Đơn thuê đã kết thúc",
                    "Đơn thuê đã kết thúc. Xem đơn để biết kết quả xử lý tiền cọc và thanh toán.")),
            Map.entry("OrderExtended", new Template("Đơn thuê đã được gia hạn",
                    "Đơn thuê đã được gia hạn theo yêu cầu đã thanh toán. Xem đơn để biết thời gian mới.")),
            Map.entry("OrderExtensionExpired", new Template("Yêu cầu gia hạn đã hết hạn thanh toán",
                    "Yêu cầu gia hạn đã hết hạn thanh toán và bị hủy. Đơn gốc không thay đổi.")),
            Map.entry("OrderExtensionFailed", new Template("Gia hạn không thành công, tiền đã được hoàn lại",
                    "Yêu cầu gia hạn không thành công. Xem đơn để biết chi tiết hoàn tiền.")),
            Map.entry("OrderCancelled", new Template("Đơn thuê đã bị hủy",
                    "Đơn thuê đã bị hủy. Xem đơn để biết chi tiết hoàn tiền.")),
            Map.entry("CredentialViewed", new Template("Người thuê đã xem thông tin đăng nhập",
                    "Người thuê vừa xem thông tin đăng nhập của đơn. Hãy theo dõi đơn nếu có bất thường.")),
            Map.entry("HandoverConfirmed", new Template("Người thuê đã đăng nhập thành công",
                    "Người thuê đã xác nhận đăng nhập được vào tài khoản. Bàn giao đã hoàn tất.")),
            Map.entry("OtpRequested", new Template("Người thuê cần mã OTP",
                    "Đơn đang chờ mã xác minh để bàn giao. Xem đơn để biết thêm chi tiết.")),
            Map.entry("GameAccountStateChanged", new Template("Trạng thái account thay đổi",
                    "Trạng thái tài khoản game đã thay đổi. Xem chi tiết trong mục tài khoản.")),
            Map.entry("RotationOverdue", new Template("Quá hạn đổi mật khẩu",
                    "Đã quá hạn đổi mật khẩu tài khoản game. Hãy đổi mật khẩu để mở lại listing.")),
            Map.entry("CredentialRotated", new Template("Đã cập nhật mật khẩu mới",
                    "Mật khẩu tài khoản game đã được cập nhật và lưu an toàn.")),
            Map.entry("DepositStateChanged", new Template("Trạng thái tiền cọc thay đổi",
                    "Tiền cọc của một đơn đã đổi trạng thái. Xem chi tiết trong đơn.")),
            Map.entry("RefundCompleted", new Template("Đã hoàn tiền vào ví",
                    "Khoản hoàn tiền đã được cộng vào ví của bạn.")),
            Map.entry("WalletToppedUp", new Template("Nạp tiền vào ví thành công",
                    "Số tiền đã được cộng vào ví. Bạn có thể dùng ngay để thanh toán.")),
            Map.entry("RefundFailed", new Template("Hoàn tiền thất bại",
                    "Một khoản hoàn tiền chưa thành công và sẽ được thử lại tự động.")),
            Map.entry("DisputeOpened", new Template("Có khiếu nại mới",
                    "Đã có khiếu nại mới trên một đơn. Xem khiếu nại để biết chi tiết và thời hạn.")),
            Map.entry("DisputeEvidenceAdded", new Template("Khiếu nại có bằng chứng mới",
                    "Bên còn lại vừa bổ sung bằng chứng cho khiếu nại. Hãy xem và phản hồi nếu cần.")),
            Map.entry("DisputeResolved", new Template("Khiếu nại đã được phân xử",
                    "Khiếu nại đã có quyết định. Xem chi tiết và kết quả thanh toán trong đơn.")),
            Map.entry("SellerHoldReleased", new Template("Doanh thu đã khả dụng",
                    "Một khoản doanh thu đã hết thời gian giữ và có thể rút.")),
            Map.entry("WithdrawalRequested", new Template("Yêu cầu rút tiền mới",
                    "Yêu cầu rút tiền đã được ghi nhận và đang chờ xử lý.")),
            Map.entry("WithdrawalPaid", new Template("Rút tiền thành công",
                    "Khoản rút tiền đã được chuyển đến tài khoản ngân hàng của bạn.")),
            Map.entry("WithdrawalFailed", new Template("Rút tiền thất bại",
                    "Khoản rút tiền chưa chuyển được. Số tiền đã được hoàn về ví của bạn.")),
            Map.entry("ListingStatusChanged", new Template("Trạng thái listing thay đổi",
                    "Trạng thái listing đã thay đổi. Xem chi tiết để biết bước tiếp theo.")),
            Map.entry("ListingSubmittedForReview", new Template("Có listing mới chờ duyệt",
                    "Một listing đã được gửi và đang chờ kiểm duyệt.")),
            Map.entry("GameAccountPendingVerification", new Template("Có account game mới chờ xác minh",
                    "Có tài khoản game đang chờ xác minh quyền khôi phục.")),
            Map.entry("GameTosStatusChanged", new Template("Trạng thái game thay đổi",
                    "Trạng thái điều khoản của một game đã thay đổi. Các listing liên quan có thể bị tạm dừng.")),
            Map.entry("ApprovalRequested", new Template("Có yêu cầu phê duyệt mới",
                    "Có yêu cầu phê duyệt đang chờ quyết định. Xem danh sách phê duyệt.")),
            Map.entry("ApprovalDecided", new Template("Yêu cầu phê duyệt đã có kết quả",
                    "Yêu cầu phê duyệt đã có kết quả. Xem chi tiết trong danh sách phê duyệt.")),
            Map.entry("UserStatusChanged", new Template("Trạng thái tài khoản thay đổi",
                    "Trạng thái tài khoản đã thay đổi. Liên hệ hỗ trợ nếu cần giải thích thêm.")),
            Map.entry("SupportTicketOpened", new Template("Có yêu cầu hỗ trợ mới",
                    "Có yêu cầu hỗ trợ mới đang chờ phản hồi.")),
            Map.entry("SupportMessagePosted", new Template("Tin nhắn mới trong yêu cầu hỗ trợ",
                    "Có tin nhắn mới trong cuộc trò chuyện hỗ trợ.")),
            Map.entry("SupportParticipantAdded", new Template("Bạn được mời vào một cuộc trò chuyện hỗ trợ",
                    "Bạn vừa được thêm vào một cuộc trò chuyện hỗ trợ.")),
            Map.entry("SupportTicketStatusChanged", new Template("Yêu cầu hỗ trợ đã được cập nhật",
                    "Trạng thái yêu cầu hỗ trợ đã thay đổi.")));

    private NotificationTemplates() {}

    public static Optional<Template> forEvent(String eventType) {
        return Optional.ofNullable(BY_EVENT.get(eventType));
    }

    /** Every event that has a template, for tests and for the notification service's own check. */
    public static java.util.Set<String> events() {
        return BY_EVENT.keySet();
    }
}
