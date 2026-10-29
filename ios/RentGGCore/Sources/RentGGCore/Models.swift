import Foundation

// Phản chiếu read model của backend (xem frontend/src/api/types.ts và các record Java trong backend/).
// Tiền là VND, số nguyên. Thời gian giữ dạng chuỗi ISO-8601, chuyển sang Date bằng `parseISODate`.
// Trường nào backend có thể bỏ trống hoặc thêm sau được khai báo optional để giải mã không bị vỡ.

public struct TokenResponse: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
}

public struct Me: Decodable, Sendable {
    public let id: String
    public let email: String
    public let displayName: String
    public let dateOfBirth: String?
    public let roles: [String]?
    public let status: String?
    public let kycStatus: String?
    public let riskTier: String?
}

public struct PageResponse<T: Decodable & Sendable>: Decodable, Sendable {
    public let content: [T]
    public let page: Int?
    public let size: Int?
    public let totalElements: Int?
    public let totalPages: Int?
}

public struct GameView: Decodable, Identifiable, Sendable {
    public let id: String
    public let code: String
    public let name: String
    public let publisher: String?
    public let tosStatus: String?
    public let handoverMode: String?
    public let depositMin: Int?
    public let depositMax: Int?
    public let minDurationMinutes: Int?
    public let maxDurationMinutes: Int?
    /// What makes one game account unique (nil from an older backend = login only).
    public let accountKey: AccountKeyRule?
    public let accountKeyLabel: String?

    public var needsAccountKey: Bool { (accountKey?.type ?? "LOGIN") != "LOGIN" }
}

public struct AccountKeyRule: Decodable, Sendable {
    public let type: String
    public let label: String?
    public let example: String?
    public let perServer: Bool?
}

public struct Listing: Decodable, Identifiable, Sendable {
    public let id: String
    public let gameAccountId: String
    public let ownerId: String
    public let gameId: String
    public let title: String
    public let description: String?
    public let attributes: [String: JSONValue]?
    public let pricePerHour: Int
    public let depositAmount: Int
    public let autoAccept: Bool?
    public let endMode: String?
    public let status: String?
    public let reviewNote: String?
    public let version: Int?
}

public struct Policy: Decodable, Sendable {
    public let handoverMode: String?
    public let depositHoldHours: Int?
    public let complaintWindowMinutes: Int?
    public let minDurationMinutes: Int?
    public let maxDurationMinutes: Int?
    public let graceMinutes: Int?
    public let renterFreeCancelMinutes: Int?
    public let lateCancelRefundBps: Int?
    public let minAge: Int?
}

public struct BusyPeriod: Decodable, Sendable {
    public let startAt: String
    public let endAt: String

    public init(startAt: String, endAt: String) {
        self.startAt = startAt
        self.endAt = endAt
    }
}

public struct ListingDetail: Decodable, Sendable {
    public let listing: Listing
    public let policy: Policy
    public let busyPeriods: [BusyPeriod]?
    public let bookable: Bool?
}

public struct Order: Decodable, Identifiable, Sendable {
    public let id: String
    public let listingId: String
    public let gameId: String
    public let renterId: String
    public let ownerId: String
    public let status: String
    public let startAt: String
    public let scheduledEndAt: String
    public let actualEndAt: String?
    public let rentAmount: Int?
    public let platformFee: Int?
    public let sellerNet: Int?
    public let depositAmount: Int?
    public let amountToPay: Int?
    public let endMode: String?
    public let paymentExpiresAt: String?
    public let approvalExpiresAt: String?
    public let credentialFirstViewedAt: String?
    public let complaintWindowEndsAt: String?
    public let handoverConfirmedAt: String?
    public let cancelReason: String?
    public let createdAt: String?
}

public struct Deposit: Decodable, Identifiable, Sendable {
    public let id: String
    public let orderId: String
    public let amount: Int
    public let status: String
    public let holdUntil: String?
    public let deductedAmount: Int?
    public let refundedAmount: Int?
}

public struct OrderDetail: Decodable, Sendable {
    public let order: Order
    public let deposit: Deposit?
    public let refunds: [JSONValue]?
}

public struct PayResponse: Decodable, Sendable {
    public let intentId: String
    public let amount: Int
    public let status: String
    public let checkoutUrl: String?
}

public struct Credentials: Decodable, Sendable {
    public let username: String
    public let password: String
    public let complaintWindowEndsAt: String?
    public let availableUntil: String?
    public let otpNote: String?
}

public struct Balances: Decodable, Sendable {
    public let reviewHold: Int
    public let disputeHold: Int
    public let available: Int
    public let payoutProcessing: Int
}

public struct Withdrawal: Decodable, Identifiable, Sendable {
    public let id: String
    public let amount: Int
    public let status: String
    public let bank: [String: JSONValue]?
    public let bankRef: String?
    public let failureReason: String?
    public let createdAt: String?
}

public struct NotificationItem: Decodable, Identifiable, Sendable {
    public let id: String
    public let type: String?
    public let title: String
    public let body: String?
    public let readAt: String?
    public let createdAt: String?
}

public struct GameAccount: Decodable, Identifiable, Sendable {
    public let id: String
    public let gameId: String
    public let serverRegion: String?
    public let loginHint: String?
    public let securityState: String
    public let rotationDueAt: String?
    public let recoveryEmailOwned: Bool?
    public let recoveryPhoneOwned: Bool?
    public let twoFactorOwned: Bool?
    public let recoveryVerifiedAt: String?
    public let accountKey: String?
}

public struct Dispute: Decodable, Identifiable, Sendable {
    public let id: String
    public let orderId: String
    public let type: String
    public let status: String
    public let description: String?
    public let evidence: [[String: JSONValue]]?
    public let deadlineAt: String?
    public let depositDeduction: Int?
    public let rentRefund: Int?
    public let reasonCode: String?
    public let decisionNote: String?
    public let createdAt: String?
}

/// Giá trị JSON tùy ý (thuộc tính listing, thông tin ngân hàng, bằng chứng khiếu nại…).
public enum JSONValue: Codable, Sendable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? c.decode(Double.self) {
            self = .number(n)
        } else if let s = try? c.decode(String.self) {
            self = .string(s)
        } else if let a = try? c.decode([JSONValue].self) {
            self = .array(a)
        } else {
            self = .object(try c.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    /// Dạng hiển thị ngắn cho thẻ thuộc tính.
    public var display: String {
        switch self {
        case .string(let s): return s
        case .number(let n): return n.rounded() == n && abs(n) < 1e15 ? String(Int(n)) : String(n)
        case .bool(let b): return b ? "Có" : "Không"
        case .null: return ""
        case .array(let a): return a.map(\.display).joined(separator: ", ")
        case .object: return ""
        }
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }
}
