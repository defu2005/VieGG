import Foundation

// Quy tắc hiển thị thao tác (đối chiếu frontend/src/pages/OrderDetailPage.tsx, MyListingsPage.tsx và
// backend DisputeService.open). Backend vẫn là nơi kiểm tra quyền thật; ở đây chỉ để ẩn nút không dùng được.

public enum DisputeType {
    public static let ownerDamage = "OWNER_DAMAGE"
    public static let accountTakeover = "ACCOUNT_TAKEOVER"
    public static let renterLoginFailed = "RENTER_LOGIN_FAILED"
    public static let renterNotAsDescribed = "RENTER_NOT_AS_DESCRIBED"

    public static let renterTypes = [renterLoginFailed, renterNotAsDescribed]
    public static let ownerTypes = [ownerDamage, accountTakeover]
}

/// Các thao tác người đang xem được phép thấy trên một đơn.
public struct OrderActions: Equatable, Sendable {
    public let isRenter: Bool
    public let isOwner: Bool
    public let canPay: Bool
    public let canApprove: Bool
    public let canConfirmHandover: Bool
    public let canViewCredentials: Bool
    public let canRequestOtp: Bool
    public let canSendOtp: Bool
    public let canCancel: Bool
    /// Loại khiếu nại được mở; rỗng nghĩa là không mở được.
    public let disputeTypes: [String]
    /// Người thuê đang còn quyền khiếu nại và sẽ mất quyền đó khi xác nhận bàn giao.
    public let handoverEndsComplaints: Bool

    public init(order: Order, viewerId: String?, handoverMode: String?, now: Date = Date()) {
        let renter = viewerId != nil && viewerId == order.renterId
        let owner = viewerId != nil && viewerId == order.ownerId
        let s = order.status
        let active = s == "CONFIRMED" || s == "IN_RENTAL"
        let otpGame = handoverMode == "OWNER_ASSISTED_OTP"

        isRenter = renter
        isOwner = owner
        canPay = renter && s == "PENDING_PAYMENT"
        canApprove = owner && s == "AWAITING_OWNER_APPROVAL"
        canConfirmHandover = renter && s == "CONFIRMED" && order.handoverConfirmedAt == nil
        canViewCredentials = renter && active
        canRequestOtp = renter && otpGame && active
        canSendOtp = owner && otpGame && active
        canCancel = (renter || owner) && ["PENDING_PAYMENT", "AWAITING_OWNER_APPROVAL", "CONFIRMED"].contains(s)

        // Khớp DisputeService.open ở backend:
        // - người thuê: đơn IN_RENTAL, còn trong hạn khiếu nại (tính từ lần xem credentials đầu tiên),
        //   và chưa xác nhận bàn giao;
        // - chủ: báo chiếm account khi IN_RENTAL hoặc ENDED, báo thiệt hại chỉ khi ENDED.
        let complaintWindowOpen = order.complaintWindowEndsAt
            .flatMap(parseISODate)
            .map { now <= $0 } ?? false
        let renterCanComplain = renter && s == "IN_RENTAL" && order.handoverConfirmedAt == nil && complaintWindowOpen
        if renterCanComplain {
            disputeTypes = DisputeType.renterTypes
        } else if owner && s == "ENDED" {
            disputeTypes = DisputeType.ownerTypes
        } else if owner && s == "IN_RENTAL" {
            disputeTypes = [DisputeType.accountTakeover]
        } else {
            disputeTypes = []
        }
        handoverEndsComplaints = renterCanComplain
    }
}

/// Thao tác trên listing của chủ account.
public struct ListingActions: Equatable, Sendable {
    public let canSubmit: Bool
    public let canPause: Bool
    public let canEdit: Bool

    public init(status: String?) {
        canSubmit = status == "DRAFT" || status == "PAUSED"
        canPause = status == "ACTIVE"
        canEdit = status != "SUSPENDED"
    }
}

// MARK: - Kiểm tra dữ liệu nhập

public enum Validation {
    /// Ngày sinh dạng YYYY-MM-DD có thật (không chấp nhận 2001-02-30).
    public static func isValidDate(_ s: String) -> Bool {
        let parts = s.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1
        else { return false }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: DateComponents(year: y, month: m, day: d)) else { return false }
        let back = calendar.dateComponents([.year, .month, .day], from: date)
        return back.year == y && back.month == m && back.day == d
    }

    /// Đủ `minAge` tuổi tính đến `today` (mặc định hôm nay).
    public static func isAtLeast(age minAge: Int, dateOfBirth s: String, today: Date = Date()) -> Bool {
        guard isValidDate(s) else { return false }
        let parts = s.split(separator: "-").compactMap { Int($0) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let birth = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              let years = calendar.dateComponents([.year], from: birth, to: today).year
        else { return false }
        return years >= minAge
    }

    public static func isValidEmail(_ s: String) -> Bool {
        let parts = s.split(separator: "@", omittingEmptySubsequences: false)
        return parts.count == 2 && !parts[0].isEmpty && parts[1].contains(".") && !s.contains(" ")
    }

    /// Số tài khoản ngân hàng: 6 đến 20 chữ số (khớp `[0-9]{6,20}` ở backend).
    public static func isValidBankAccount(_ s: String) -> Bool {
        (6...20).contains(s.count) && s.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Mỗi dòng một link; bỏ dòng trống và khoảng trắng hai đầu.
    public static func parseUrls(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// Backend chỉ nhận link `https://`.
    public static func invalidEvidenceUrls(_ urls: [String]) -> [String] {
        urls.filter { url in
            guard let comps = URLComponents(string: url), comps.scheme?.lowercased() == "https",
                  let host = comps.host, !host.isEmpty
            else { return true }
            return false
        }
    }
}

// MARK: - Đặt thuê

public enum Booking {
    public static let stepMinutes = 30

    /// Các lựa chọn thời lượng (bước 30 phút) trong khoảng cho phép của game.
    public static func durationOptions(min: Int, max: Int) -> [Int] {
        let lo = Swift.max(stepMinutes, min)
        guard lo <= max else { return [min] }
        var options: [Int] = []
        var d = lo
        while d <= max {
            options.append(d)
            d += stepMinutes
        }
        if options.last != max { options.append(max) }
        return options
    }

    /// Lịch đã bận chồng lên khoảng thuê dự định (hai đầu mở: kết thúc đúng lúc bắt đầu thì không chồng).
    public static func overlapping(start: Date, durationMinutes: Int, busy: [BusyPeriod]) -> BusyPeriod? {
        let end = start.addingTimeInterval(TimeInterval(durationMinutes * 60))
        return busy.first { period in
            guard let s = parseISODate(period.startAt), let e = parseISODate(period.endAt) else { return false }
            return s < end && start < e
        }
    }

    /// Tiền thuê ước tính (backend mới là nơi tính chính thức, có thể làm tròn khác).
    public static func estimatedRent(pricePerHour: Int, durationMinutes: Int) -> Int {
        (pricePerHour * durationMinutes + 59) / 60
    }

    /// Mặc định: tròn lên mốc 30 phút kế tiếp, cách hiện tại ít nhất 1 giờ (giống web).
    public static func defaultStart(now: Date = Date()) -> Date {
        let target = now.addingTimeInterval(3600)
        let step = TimeInterval(stepMinutes * 60)
        return Date(timeIntervalSince1970: (target.timeIntervalSince1970 / step).rounded(.up) * step)
    }
}
