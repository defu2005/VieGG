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
                    "Đơn của bạn đã được tạo. Thanh toán trong thời hạn để giữ chỗ.")),
            Map.entry("OrderPaid", new Template("Thanh toán thành công",
                    "Chúng tôi đã nhận được thanh toán. Đơn đang chờ đến giờ bắt đầu.")),
            Map.entry("OrderAwaitingApproval", new Template("Có đơn thuê cần bạn duyệt",
                    "Người thuê đã đặt listing của bạn. Hãy duyệt hoặc từ chối trước hạn.")),
            Map.entry("OrderConfirmed", new Template("Đơn thuê đã được xác nhận",
                    "Chủ listing đã xác nhận đơn. Hãy chờ đến giờ bắt đầu thuê.")),
            Map.entry("OrderRejected", new Template("Đơn thuê bị từ chối",
                    "Chủ listing đã từ chối đơn. Số tiền đã thanh toán sẽ được hoàn lại.")),
            Map.entry("OrderExpired", new Template("Đơn thuê hết hạn thanh toán",
                    "Đơn đã hết hạn vì chưa thanh toán đúng hạn. Chỗ thuê đã được mở lại.")),
            Map.entry("OrderStarted", new Template("Đơn thuê đã bắt đầu, thông tin đăng nhập đã sẵn sàng",
                    "Đơn thuê đã bắt đầu. Mở đơn để xem thông tin đăng nhập và bàn giao.")),
            Map.entry("OrderEndingSoon", new Template("Đơn thuê sắp hết giờ",
                    "Đơn thuê sắp kết thúc. Hãy lưu tiến độ và chuẩn bị trả tài khoản.")),
            Map.entry("OrderEnded", new Template("Đơn thuê đã kết thúc",
                    "Đơn thuê đã kết thúc. Tiền cọc và doanh thu sẽ được xử lý theo quy định.")),
            Map.entry("OrderExtended", new Template("Đơn thuê đã được gia hạn",
                    "Thời gian thuê đã được gia hạn theo yêu cầu đã thanh toán.")),
            Map.entry("OrderExtensionExpired", new Template("Yêu cầu gia hạn đã hết hạn thanh toán",
                    "Yêu cầu gia hạn chưa được thanh toán đúng hạn nên đã bị hủy. Đơn gốc không thay đổi.")),
            Map.entry("OrderExtensionFailed", new Template("Gia hạn không thành công, tiền đã được hoàn lại",
                    "Không thể gia hạn đơn. Khoản thanh toán gia hạn đã được hoàn vào ví.")),
            Map.entry("OrderCancelled", new Template("Đơn thuê đã bị hủy",
                    "Đơn thuê đã được hủy. Mọi khoản đã thu sẽ được hoàn theo chính sách hủy.")),
            Map.entry("CredentialViewed", new Template("Người thuê đã xem thông tin đăng nhập",
                    "Người thuê vừa xem thông tin đăng nhập của đơn. Hãy theo dõi đơn nếu có bất thường.")),
            Map.entry("HandoverConfirmed", new Template("Người thuê đã đăng nhập thành công",
                    "Người thuê xác nhận đã đăng nhập được vào tài khoản. Bàn giao hoàn tất.")),
            Map.entry("OtpRequested", new Template("Người thuê cần mã OTP",
                    "Đơn cần mã xác minh để bàn giao. Hãy cung cấp mã cho người thuê khi được yêu cầu.")),
            Map.entry("GameAccountStateChanged", new Template("Trạng thái account thay đổi",
                    "Trạng thái tài khoản game của bạn đã thay đổi. Xem chi tiết trong mục tài khoản.")),
            Map.entry("RotationOverdue", new Template("Quá hạn đổi mật khẩu",
                    "Bạn chưa đổi mật khẩu tài khoản game đúng hạn. Hãy đổi mật khẩu ngay để mở lại listing.")),
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
                    "Một khiếu nại mới cần được xem xét. Kiểm tra bằng chứng và thời hạn phân xử.")),
            Map.entry("DisputeEvidenceAdded", new Template("Khiếu nại có bằng chứng mới",
                    "Bên còn lại vừa bổ sung bằng chứng cho khiếu nại. Hãy xem và phản hồi nếu cần.")),
            Map.entry("DisputeResolved", new Template("Khiếu nại đã được phân xử",
                    "Khiếu nại đã có quyết định. Xem chi tiết và kết quả thanh toán trong đơn.")),
            Map.entry("SellerHoldReleased", new Template("Doanh thu đã khả dụng",
                    "Một khoản doanh thu đã hết thời gian giữ và có thể rút.")),
            Map.entry("WithdrawalRequested", new Template("Yêu cầu rút tiền mới",
                    "Có yêu cầu rút tiền mới đang chờ xử lý.")),
            Map.entry("WithdrawalPaid", new Template("Rút tiền thành công",
                    "Khoản rút tiền đã được chuyển đến tài khoản ngân hàng của bạn.")),
            Map.entry("WithdrawalFailed", new Template("Rút tiền thất bại",
                    "Khoản rút tiền chưa chuyển được. Số tiền đã được hoàn về ví của bạn.")),
            Map.entry("ListingStatusChanged", new Template("Trạng thái listing thay đổi",
                    "Listing của bạn đã đổi trạng thái. Xem chi tiết để biết bước tiếp theo.")),
            Map.entry("ListingSubmittedForReview", new Template("Có listing mới chờ duyệt",
                    "Một listing mới đã được gửi và đang chờ kiểm duyệt.")),
            Map.entry("GameAccountPendingVerification", new Template("Có account game mới chờ xác minh",
                    "Một tài khoản game mới cần xác minh quyền khôi phục.")),
            Map.entry("GameTosStatusChanged", new Template("Trạng thái game thay đổi",
                    "Trạng thái điều khoản của một game đã thay đổi. Các listing liên quan có thể bị tạm dừng.")),
            Map.entry("ApprovalRequested", new Template("Có yêu cầu phê duyệt mới",
                    "Một yêu cầu cần hai người duyệt đang chờ bạn quyết định.")),
            Map.entry("ApprovalDecided", new Template("Yêu cầu phê duyệt đã có kết quả",
                    "Yêu cầu phê duyệt bạn tạo đã có kết quả. Xem chi tiết trong danh sách phê duyệt.")),
            Map.entry("UserStatusChanged", new Template("Trạng thái tài khoản thay đổi",
                    "Trạng thái tài khoản của bạn đã thay đổi. Liên hệ hỗ trợ nếu cần giải thích thêm.")),
            Map.entry("SupportTicketOpened", new Template("Có yêu cầu hỗ trợ mới",
                    "Một yêu cầu hỗ trợ mới đang chờ phản hồi.")),
            Map.entry("SupportMessagePosted", new Template("Tin nhắn mới trong yêu cầu hỗ trợ",
                    "Có tin nhắn mới trong cuộc trò chuyện hỗ trợ.")),
            Map.entry("SupportParticipantAdded", new Template("Bạn được mời vào một cuộc trò chuyện hỗ trợ",
                    "Bạn vừa được thêm vào một cuộc trò chuyện hỗ trợ.")),
            Map.entry("SupportTicketStatusChanged", new Template("Yêu cầu hỗ trợ đã được cập nhật",
                    "Trạng thái yêu cầu hỗ trợ của bạn đã thay đổi.")));

    private NotificationTemplates() {}

    public static Optional<Template> forEvent(String eventType) {
        return Optional.ofNullable(BY_EVENT.get(eventType));
    }

    /** Every event that has a template, for tests and for the notification service's own check. */
    public static java.util.Set<String> events() {
        return BY_EVENT.keySet();
    }
}
