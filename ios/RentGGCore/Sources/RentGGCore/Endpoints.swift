import Foundation

// Các endpoint người dùng cuối cần (thuê và cho thuê). Màn staff (admin, support, finance) dùng web.
// Đường dẫn khớp với controller trong backend/src/main/java/com/rentgg/*/api.

// MARK: - Request bodies

struct LoginBody: Encodable { let email: String; let password: String }

struct RegisterBody: Encodable {
    let email: String
    let password: String
    let displayName: String
    let dateOfBirth: String
}

struct KycBody: Encodable { let fullName: String; let idNumber: String; let phone: String? }

struct CreateOrderBody: Encodable { let listingId: String; let startAt: String; let durationMinutes: Int }

struct OtpNoteBody: Encodable { let note: String }

struct WithdrawBody: Encodable {
    let amount: Int
    let bankName: String
    let bankAccountNumber: String
    let bankAccountName: String
}

struct CreateGameAccountBody: Encodable {
    let gameId: String
    let serverRegion: String?
    let username: String
    let password: String
    let recoveryEmailOwned: Bool
    let recoveryPhoneOwned: Bool
    let twoFactorOwned: Bool
    let accountKey: String?
}

struct RotationBody: Encodable { let newUsername: String?; let newPassword: String; let loggedOutAllDevices: Bool }

struct ListingBody: Encodable {
    let gameAccountId: String?
    let version: Int?
    let title: String
    let description: String?
    let pricePerHour: Int
    let depositAmount: Int
    let autoAccept: Bool
    let endMode: String
}

struct OpenDisputeBody: Encodable { let type: String; let description: String; let evidenceUrls: [String] }

struct EvidenceBody: Encodable { let evidenceUrls: [String]; let note: String? }

/// Nội dung listing do chủ account nhập, dùng chung cho tạo mới và sửa.
public struct ListingDraft: Sendable, Equatable {
    public var title: String
    public var description: String
    public var pricePerHour: Int
    public var depositAmount: Int
    public var autoAccept: Bool
    public var endMode: String

    public init(
        title: String = "",
        description: String = "",
        pricePerHour: Int = 30_000,
        depositAmount: Int = 200_000,
        autoAccept: Bool = false,
        endMode: String = EndMode.forceAtEnd
    ) {
        self.title = title
        self.description = description
        self.pricePerHour = pricePerHour
        self.depositAmount = depositAmount
        self.autoAccept = autoAccept
        self.endMode = endMode
    }

    public init(listing: Listing) {
        self.init(
            title: listing.title,
            description: listing.description ?? "",
            pricePerHour: listing.pricePerHour,
            depositAmount: listing.depositAmount,
            autoAccept: listing.autoAccept ?? false,
            endMode: listing.endMode ?? EndMode.forceAtEnd
        )
    }

    public var isValid: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && title.count <= 150 && description.count <= 5000
            && pricePerHour > 0 && depositAmount > 0
    }

    func body(gameAccountId: String?, version: Int?) -> ListingBody {
        let desc = description.trimmingCharacters(in: .whitespacesAndNewlines)
        return ListingBody(
            gameAccountId: gameAccountId,
            version: version,
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            description: desc.isEmpty ? nil : desc,
            pricePerHour: pricePerHour,
            depositAmount: depositAmount,
            autoAccept: autoAccept,
            endMode: endMode
        )
    }
}

public enum OrderRole: String, Sendable, CaseIterable {
    case renter = "RENTER"
    case owner = "OWNER"
}

// MARK: - Endpoints

public extension APIClient {
    // Tài khoản

    func login(email: String, password: String) async throws {
        let data = try await send("POST", "auth/login", body: try encode(LoginBody(email: email, password: password)), anonymous: true)
        let t = try decode(TokenResponse.self, from: data)
        tokens.save(access: t.accessToken, refresh: t.refreshToken)
    }

    func register(email: String, password: String, displayName: String, dateOfBirth: String) async throws {
        let body = RegisterBody(email: email, password: password, displayName: displayName, dateOfBirth: dateOfBirth)
        try await send("POST", "auth/register", body: try encode(body), anonymous: true)
    }

    func logout() {
        tokens.clear()
    }

    var hasSession: Bool { tokens.hasSession }

    func me() async throws -> Me {
        try await get("me")
    }

    func submitKyc(fullName: String, idNumber: String, phone: String?) async throws {
        try await send("POST", "me/kyc", body: try encode(KycBody(fullName: fullName, idNumber: idNumber, phone: phone)))
    }

    // Game và listing

    func games() async throws -> [GameView] {
        try await get("games")
    }

    func game(id: String) async throws -> GameView {
        try await get("games/\(id)")
    }

    func searchListings(gameId: String? = nil, size: Int = 50) async throws -> [Listing] {
        var query = ["size": String(size)]
        if let gameId { query["gameId"] = gameId }
        let page: PageResponse<Listing> = try await get("listings/search", query: query)
        return page.content
    }

    func listing(id: String) async throws -> ListingDetail {
        try await get("listings/\(id)")
    }

    /// Listing của chủ, mới nhất trước (API phân trang; lấy trang đầu, tối đa 100).
    func myListings(size: Int = 100) async throws -> [Listing] {
        let page: PageResponse<Listing> = try await get("listings/mine", query: ["size": String(size)])
        return page.content
    }

    func createListing(gameAccountId: String, draft: ListingDraft) async throws {
        try await send("POST", "listings", body: try encode(draft.body(gameAccountId: gameAccountId, version: nil)))
    }

    /// Gửi kèm `version` để backend chặn ghi đè khi listing đã bị sửa ở nơi khác.
    func updateListing(_ listing: Listing, draft: ListingDraft) async throws {
        try await send("PUT", "listings/\(listing.id)", body: try encode(draft.body(gameAccountId: nil, version: listing.version)))
    }

    func submitListing(id: String) async throws {
        try await send("POST", "listings/\(id)/submit")
    }

    func pauseListing(id: String) async throws {
        try await send("POST", "listings/\(id)/pause")
    }

    // Đơn thuê

    func orders(role: OrderRole, size: Int = 50) async throws -> [Order] {
        let page: PageResponse<Order> = try await get("orders", query: ["role": role.rawValue, "size": String(size)])
        return page.content
    }

    func order(id: String) async throws -> OrderDetail {
        try await get("orders/\(id)")
    }

    func createOrder(listingId: String, startAt: Date, durationMinutes: Int) async throws -> Order {
        let body = CreateOrderBody(listingId: listingId, startAt: isoString(startAt), durationMinutes: durationMinutes)
        return try decode(Order.self, from: try await send("POST", "orders", body: try encode(body), idempotent: true))
    }

    func pay(orderId: String) async throws -> PayResponse {
        try decode(PayResponse.self, from: try await send("POST", "orders/\(orderId)/pay", idempotent: true))
    }

    /// Chỉ có ở profile `dev` của backend: giả lập cổng thanh toán báo thành công.
    func simulatePayment(intentId: String) async throws {
        try await send("POST", "dev/payments/\(intentId)/simulate-success")
    }

    func approveOrder(id: String) async throws {
        try await send("POST", "orders/\(id)/approve")
    }

    func rejectOrder(id: String) async throws {
        try await send("POST", "orders/\(id)/reject")
    }

    func cancelOrder(id: String) async throws {
        try await send("POST", "orders/\(id)/cancel")
    }

    func confirmHandover(orderId: String) async throws {
        try await send("POST", "orders/\(orderId)/handover/confirm")
    }

    /// Mỗi lần xem đều được backend ghi nhật ký.
    func credentials(orderId: String) async throws -> Credentials {
        try await get("orders/\(orderId)/credentials")
    }

    func requestOtp(orderId: String) async throws {
        try await send("POST", "orders/\(orderId)/otp-request")
    }

    func sendOtpNote(orderId: String, note: String) async throws {
        try await send("POST", "orders/\(orderId)/otp-note", body: try encode(OtpNoteBody(note: note)))
    }

    // Khiếu nại

    func disputes(orderId: String) async throws -> [Dispute] {
        try await get("orders/\(orderId)/disputes")
    }

    func openDispute(orderId: String, type: String, description: String, evidenceUrls: [String]) async throws {
        let body = OpenDisputeBody(type: type, description: description, evidenceUrls: evidenceUrls)
        try await send("POST", "orders/\(orderId)/disputes", body: try encode(body))
    }

    func addEvidence(disputeId: String, evidenceUrls: [String], note: String?) async throws {
        try await send("POST", "disputes/\(disputeId)/evidence", body: try encode(EvidenceBody(evidenceUrls: evidenceUrls, note: note)))
    }

    // Ví

    func balances() async throws -> Balances {
        try await get("wallet/balances")
    }

    func deposits(size: Int = 50) async throws -> [Deposit] {
        let page: PageResponse<Deposit> = try await get("wallet/deposits", query: ["size": String(size)])
        return page.content
    }

    func withdrawals(size: Int = 50) async throws -> [Withdrawal] {
        let page: PageResponse<Withdrawal> = try await get("wallet/withdrawals", query: ["size": String(size)])
        return page.content
    }

    func withdraw(amount: Int, bankName: String, accountNumber: String, accountName: String) async throws {
        let body = WithdrawBody(amount: amount, bankName: bankName, bankAccountNumber: accountNumber, bankAccountName: accountName)
        try await send("POST", "wallet/withdrawals", body: try encode(body), idempotent: true)
    }

    // Account game của chủ

    /// Account game của chủ, mới nhất trước (API phân trang; lấy trang đầu, tối đa 100).
    func gameAccounts(size: Int = 100) async throws -> [GameAccount] {
        let page: PageResponse<GameAccount> = try await get("game-accounts", query: ["size": String(size)])
        return page.content
    }

    func createGameAccount(
        gameId: String,
        serverRegion: String?,
        username: String,
        password: String,
        recoveryEmailOwned: Bool,
        recoveryPhoneOwned: Bool,
        twoFactorOwned: Bool,
        accountKey: String? = nil
    ) async throws {
        let body = CreateGameAccountBody(
            gameId: gameId, serverRegion: serverRegion, username: username, password: password,
            recoveryEmailOwned: recoveryEmailOwned, recoveryPhoneOwned: recoveryPhoneOwned, twoFactorOwned: twoFactorOwned,
            accountKey: accountKey
        )
        try await send("POST", "game-accounts", body: try encode(body))
    }

    func rotateCredentials(accountId: String, newUsername: String?, newPassword: String, loggedOutAllDevices: Bool) async throws {
        let body = RotationBody(newUsername: newUsername, newPassword: newPassword, loggedOutAllDevices: loggedOutAllDevices)
        try await send("POST", "game-accounts/\(accountId)/rotation", body: try encode(body))
    }

    func reclaim(accountId: String) async throws {
        try await send("POST", "game-accounts/\(accountId)/reclaim")
    }

    // Thông báo

    func notifications(unreadOnly: Bool = false, size: Int = 50) async throws -> PageResponse<NotificationItem> {
        var query = ["size": String(size)]
        if unreadOnly { query["unread"] = "true" }
        return try await get("notifications", query: query)
    }

    func unreadCount() async throws -> Int {
        try await notifications(unreadOnly: true, size: 1).totalElements ?? 0
    }

    func markRead(notificationId: String) async throws {
        try await send("POST", "notifications/\(notificationId)/read")
    }
}
