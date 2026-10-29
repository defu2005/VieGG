import Foundation

// Định dạng hiển thị (tương đương ui/Format.kt bên Android và lib/format.ts bên web).

private let viLocale = Locale(identifier: "vi_VN")

/// Tiền VND: "30.000đ". Tự gom nhóm bằng dấu chấm để không phụ thuộc dữ liệu locale của hệ điều hành.
public func vnd(_ amount: Int) -> String {
    let digits = String(abs(amount))
    var grouped = ""
    for (i, ch) in digits.enumerated() {
        if i > 0, (digits.count - i) % 3 == 0 { grouped.append(".") }
        grouped.append(ch)
    }
    return (amount < 0 ? "-" : "") + grouped + "đ"
}

/// Đọc thời điểm ISO-8601 từ backend. Postgres/Jackson có thể trả tới 9 chữ số thập phân
/// (ví dụ `2026-10-26T08:00:00.123456Z`) mà `ISO8601DateFormatter` chỉ chắc chắn đọc được 3,
/// nên phần thập phân được cắt về mili giây trước khi đọc.
public func parseISODate(_ string: String) -> Date? {
    var s = string.trimmingCharacters(in: .whitespaces)
    guard let tIndex = s.firstIndex(of: "T") else { return nil }

    var hasFraction = false
    if let dot = s[tIndex...].firstIndex(of: ".") {
        let fracStart = s.index(after: dot)
        var fracEnd = fracStart
        while fracEnd < s.endIndex, s[fracEnd].isASCII, s[fracEnd].isNumber {
            fracEnd = s.index(after: fracEnd)
        }
        let digits = String(s[fracStart..<fracEnd])
        let millis = String((digits + "000").prefix(3))
        s.replaceSubrange(fracStart..<fracEnd, with: millis)
        hasFraction = true
    }

    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = hasFraction ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
    return formatter.date(from: s)
}

/// Chuỗi ISO-8601 (UTC, không phần thập phân) gửi lên backend, ví dụ `2026-10-27T02:00:00Z`.
public func isoString(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.string(from: date)
}

/// "HH:mm dd/MM/yyyy" theo múi giờ của máy (hoặc múi giờ truyền vào, để test).
public func fmtTime(_ iso: String?, timeZone: TimeZone = .current) -> String {
    guard let iso, let date = parseISODate(iso) else { return "—" }
    let formatter = DateFormatter()
    formatter.locale = viLocale
    formatter.timeZone = timeZone
    formatter.dateFormat = "HH:mm dd/MM/yyyy"
    return formatter.string(from: date)
}

public func durationText(minutes: Int) -> String {
    let h = minutes / 60
    let m = minutes % 60
    switch (h, m) {
    case (0, _): return "\(m) phút"
    case (_, 0): return "\(h) giờ"
    default: return "\(h) giờ \(m) phút"
    }
}

// MARK: - Nhãn trạng thái

/// Sắc thái màu của nhãn; app iOS ánh xạ sang màu cụ thể.
public enum Tone: Sendable {
    case accent, info, success, warning, danger, neutral
}

public struct StatusLabel: Sendable, Equatable {
    public let text: String
    public let tone: Tone
}

public func orderStatusLabel(_ s: String) -> StatusLabel {
    switch s {
    case "PENDING_PAYMENT": StatusLabel(text: "Chờ thanh toán", tone: .accent)
    case "AWAITING_OWNER_APPROVAL": StatusLabel(text: "Chờ chủ duyệt", tone: .info)
    case "CONFIRMED": StatusLabel(text: "Đã xác nhận", tone: .info)
    case "IN_RENTAL": StatusLabel(text: "Đang thuê", tone: .success)
    case "ENDED": StatusLabel(text: "Hoàn thành", tone: .neutral)
    case "CANCELLED": StatusLabel(text: "Đã hủy", tone: .neutral)
    case "EXPIRED": StatusLabel(text: "Hết hạn", tone: .neutral)
    case "REJECTED": StatusLabel(text: "Bị từ chối", tone: .neutral)
    default: StatusLabel(text: s, tone: .neutral)
    }
}

public func listingStatusLabel(_ s: String?) -> StatusLabel {
    switch s {
    case "DRAFT": StatusLabel(text: "Nháp", tone: .neutral)
    case "PENDING_REVIEW": StatusLabel(text: "Chờ duyệt", tone: .info)
    case "ACTIVE": StatusLabel(text: "Đang cho thuê", tone: .success)
    case "PAUSED": StatusLabel(text: "Tạm dừng", tone: .accent)
    case "REJECTED": StatusLabel(text: "Bị từ chối", tone: .danger)
    case "SUSPENDED": StatusLabel(text: "Bị đình chỉ", tone: .danger)
    default: StatusLabel(text: s ?? "—", tone: .neutral)
    }
}

public func depositStatusLabel(_ s: String) -> StatusLabel {
    switch s {
    case "PENDING_PAYMENT": StatusLabel(text: "Chờ thanh toán", tone: .accent)
    case "HELD": StatusLabel(text: "Đang giữ", tone: .info)
    case "POST_RENTAL_HOLD": StatusLabel(text: "Giữ sau thuê", tone: .info)
    case "DISPUTED": StatusLabel(text: "Đang khiếu nại", tone: .warning)
    case "REFUND_PENDING": StatusLabel(text: "Chờ hoàn", tone: .info)
    case "REFUNDED": StatusLabel(text: "Đã hoàn", tone: .success)
    case "SETTLED": StatusLabel(text: "Đã tất toán", tone: .neutral)
    case "VOID": StatusLabel(text: "Hủy", tone: .neutral)
    default: StatusLabel(text: s, tone: .neutral)
    }
}

public func withdrawalStatusLabel(_ s: String) -> StatusLabel {
    switch s {
    case "REQUESTED": StatusLabel(text: "Chờ chuyển khoản", tone: .info)
    case "PAID": StatusLabel(text: "Đã chuyển", tone: .success)
    case "FAILED": StatusLabel(text: "Thất bại", tone: .danger)
    default: StatusLabel(text: s, tone: .neutral)
    }
}

public func disputeStatusLabel(_ s: String) -> StatusLabel {
    switch s {
    case "OPEN": StatusLabel(text: "Đang xử lý", tone: .warning)
    case "RESOLVED": StatusLabel(text: "Đã giải quyết", tone: .success)
    case "REJECTED": StatusLabel(text: "Bị bác", tone: .danger)
    default: StatusLabel(text: s, tone: .neutral)
    }
}

public func securityStateLabel(_ s: String) -> StatusLabel {
    switch s {
    case "PENDING_VERIFICATION": StatusLabel(text: "Chờ xác minh", tone: .info)
    case "AVAILABLE": StatusLabel(text: "Sẵn sàng", tone: .success)
    case "IN_USE": StatusLabel(text: "Đang được thuê", tone: .info)
    case "ROTATION_PENDING": StatusLabel(text: "Cần đổi mật khẩu", tone: .warning)
    case "SUSPENDED_SECURITY_REVIEW": StatusLabel(text: "Tạm khóa rà soát", tone: .danger)
    default: StatusLabel(text: s, tone: .neutral)
    }
}

public func kycText(_ s: String?) -> String {
    switch s {
    case "NONE", nil: "Chưa nộp"
    case "PENDING": "Chờ duyệt"
    case "VERIFIED": "Đã xác minh"
    case "REJECTED": "Bị từ chối"
    default: s ?? ""
    }
}

public enum EndMode {
    public static let forceAtEnd = "FORCE_AT_END"
    public static let graceForCurrentMatch = "GRACE_FOR_CURRENT_MATCH"
    public static let stayOnlineUntilReclaim = "STAY_ONLINE_UNTIL_RECLAIM"
    public static let all = [forceAtEnd, graceForCurrentMatch, stayOnlineUntilReclaim]
}

public func endModeText(_ m: String?) -> String {
    switch m {
    case EndMode.forceAtEnd: "Hết giờ là đổi mật khẩu ngay"
    case EndMode.graceForCurrentMatch: "Cho thêm thời gian để kết thúc trận"
    case EndMode.stayOnlineUntilReclaim: "Giữ đăng nhập đến khi chủ lấy lại"
    default: m ?? ""
    }
}

public func handoverText(_ mode: String?) -> String {
    switch mode {
    case "OWNER_ASSISTED_OTP": "Chủ account hỗ trợ OTP"
    case "STATIC_CREDENTIAL": "Xem thông tin đăng nhập trực tiếp"
    default: mode ?? "—"
    }
}

public func disputeTypeText(_ t: String) -> String {
    switch t {
    case DisputeType.ownerDamage: "Chủ account báo thiệt hại"
    case DisputeType.accountTakeover: "Báo bị chiếm account"
    case DisputeType.renterLoginFailed: "Không đăng nhập được"
    case DisputeType.renterNotAsDescribed: "Account không đúng mô tả"
    default: t
    }
}
