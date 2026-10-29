import Foundation
import XCTest
@testable import RentGGCore

/// Chạy RentGGCore với backend thật (profile `dev`, có dữ liệu mẫu của DevDataSeeder).
/// Bỏ qua trừ khi đặt biến môi trường, ví dụ:
///
///     RENTGG_E2E_BASE_URL=http://localhost:8080/api/v1/ RENTGG_E2E_PASSWORD='RentGG-dev-2026!' swift test --filter IntegrationTests
///
/// Mỗi lần chạy tạo đơn và listing nháp mới trong DB dev, không đụng dữ liệu mẫu có sẵn.
final class IntegrationTests: XCTestCase {
    private var baseURL: URL!
    private var password = ""

    override func setUpWithError() throws {
        let env = ProcessInfo.processInfo.environment
        guard let raw = env["RENTGG_E2E_BASE_URL"], let url = URL(string: raw) else {
            throw XCTSkip("Đặt RENTGG_E2E_BASE_URL để chạy test tích hợp với backend thật.")
        }
        baseURL = url
        password = env["RENTGG_E2E_PASSWORD"] ?? "RentGG-dev-2026!"
    }

    /// Phiên đăng nhập dùng chung giữa các test: backend giới hạn 5 lần đăng nhập/phút và 20 lần/giờ
    /// cho mỗi email, giống app thật chỉ đăng nhập một lần rồi dùng refresh token.
    private static var sessions: [String: APIClient] = [:]

    private func client(_ email: String) async throws -> APIClient {
        if let cached = Self.sessions[email] { return cached }
        let api = APIClient(baseURL: baseURL, tokens: TokenStore(store: InMemorySecureStore()))
        try await api.login(email: email, password: password)
        Self.sessions[email] = api
        return api
    }

    /// Mốc bắt đầu sớm nhất (tròn phút, sau `after`) không trùng lịch bận của listing.
    private func freeStart(after: Date, durationMinutes: Int, busy: [BusyPeriod]) -> Date {
        var start = Date(timeIntervalSince1970: (after.timeIntervalSince1970 / 60).rounded(.up) * 60)
        // Chừa 3 phút đệm sau mỗi lịch bận (thời gian ân hạn của game và độ lệch đồng hồ).
        while let clash = Booking.overlapping(start: start, durationMinutes: durationMinutes + 3, busy: busy),
              let end = parseISODate(clash.endAt) {
            start = Date(timeIntervalSince1970: (end.timeIntervalSince1970 / 60).rounded(.up) * 60 + 180)
        }
        return start
    }

    // MARK: - Đọc dữ liệu và làm mới token

    func testReadEndpointsDecodeRealResponses() async throws {
        let renter = try await client("renter@rentgg.dev")

        let me = try await renter.me()
        XCTAssertEqual(me.email, "renter@rentgg.dev")
        XCTAssertNotNil(me.dateOfBirth)

        let games = try await renter.games()
        XCTAssertTrue(games.contains { $0.code == "quick-game" })

        let listings = try await renter.searchListings()
        XCTAssertFalse(listings.isEmpty)
        let detail = try await renter.listing(id: try XCTUnwrap(listings.first).id)
        XCTAssertNotNil(detail.policy.minDurationMinutes)
        for period in detail.busyPeriods ?? [] {
            XCTAssertNotNil(parseISODate(period.startAt), "không đọc được \(period.startAt)")
        }

        let orders = try await renter.orders(role: .renter)
        XCTAssertFalse(orders.isEmpty, "renter có đơn mẫu")
        for order in orders {
            XCTAssertNotNil(parseISODate(order.startAt), "không đọc được \(order.startAt)")
            if let created = order.createdAt {
                XCTAssertNotNil(parseISODate(created), "không đọc được \(created)")
            }
        }
        let first = try await renter.order(id: try XCTUnwrap(orders.first).id)
        XCTAssertEqual(first.order.renterId, me.id)

        _ = try await renter.balances()
        _ = try await renter.deposits()
        _ = try await renter.withdrawals()
        let inbox = try await renter.notifications()
        if let unread = inbox.content.first(where: { $0.readAt == nil }) {
            let before = try await renter.unreadCount()
            try await renter.markRead(notificationId: unread.id)
            let after = try await renter.unreadCount()
            XCTAssertEqual(after, before - 1)
        }
    }

    func testExpiredAccessTokenIsRefreshedTransparently() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        let api = APIClient(baseURL: baseURL, tokens: tokens, transport: URLSessionTransport())
        try await api.login(email: "renter@rentgg.dev", password: password)
        let refresh = try XCTUnwrap(tokens.refreshToken)

        tokens.save(access: "token-hong", refresh: refresh)
        let me = try await api.me()

        XCTAssertEqual(me.email, "renter@rentgg.dev")
        XCTAssertNotEqual(tokens.accessToken, "token-hong")
    }

    func testWrongPasswordReturnsBackendMessage() async throws {
        let api = APIClient(baseURL: baseURL, tokens: TokenStore(store: InMemorySecureStore()))
        do {
            try await api.login(email: "renter@rentgg.dev", password: "sai-mat-khau")
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error.status, 401)
            XCTAssertNotEqual(error.code, "UNKNOWN")
            XCTAssertFalse(error.message.isEmpty)
        }
    }

    // MARK: - Đặt đơn rồi hủy

    func testCreateAndCancelUnpaidOrder() async throws {
        let renter = try await client("renter@rentgg.dev")
        let all = try await renter.searchListings()
        let listing = try XCTUnwrap(all.first { $0.title == "Account mẫu rank Kim Cương" })
        let detail = try await renter.listing(id: listing.id)
        let duration = detail.policy.minDurationMinutes ?? 60
        // Rải ngày xa trong tương lai để các lần chạy không trùng nhau.
        let days = Double(Int.random(in: 20...300))
        let start = freeStart(after: Date().addingTimeInterval(days * 86_400), durationMinutes: duration, busy: detail.busyPeriods ?? [])

        let order = try await renter.createOrder(listingId: listing.id, startAt: start, durationMinutes: duration)
        XCTAssertEqual(order.status, "PENDING_PAYMENT")
        XCTAssertEqual(parseISODate(order.startAt), start)
        XCTAssertEqual(order.amountToPay, (order.rentAmount ?? 0) + (order.depositAmount ?? 0))

        try await renter.cancelOrder(id: order.id)
        let after = try await renter.order(id: order.id)
        XCTAssertEqual(after.order.status, "CANCELLED")
    }

    // MARK: - Trọn vòng đời một đơn trên game "quick-game" (thời lượng 5 phút)

    func testFullRentalLifecycleOnQuickGame() async throws {
        let renter = try await client("renter@rentgg.dev")
        let owner = try await client("owner@rentgg.dev")
        let admin = try await client("admin@rentgg.dev")
        let renterId = try await renter.me().id

        // Sau mỗi lần thuê, backend buộc chủ đổi mật khẩu (ROTATION_PENDING) trước khi nhận đơn mới;
        // listing tạm biến khỏi kết quả tìm kiếm. Chủ đổi mật khẩu như trên app rồi mới đặt.
        let mine = try await owner.myListings()
        let listing = try XCTUnwrap(mine.first { $0.title == "Account demo nhanh 1" })
        let ownerAccounts = try await owner.gameAccounts()
        let account = try XCTUnwrap(ownerAccounts.first { $0.id == listing.gameAccountId })
        if account.securityState == "ROTATION_PENDING" {
            try await owner.rotateCredentials(accountId: account.id, newUsername: nil,
                                              newPassword: "quick-\(UUID().uuidString.prefix(8))", loggedOutAllDevices: true)
            let rotated = try await owner.gameAccounts().first { $0.id == account.id }
            XCTAssertNotEqual(rotated?.securityState, "ROTATION_PENDING")
        }
        let searchable = try await renter.searchListings()
        XCTAssertTrue(searchable.contains { $0.id == listing.id }, "listing phải tìm thấy được sau khi đổi mật khẩu")

        let detail = try await renter.listing(id: listing.id)
        let duration = detail.policy.minDurationMinutes ?? 5
        let start = freeStart(after: Date().addingTimeInterval(75), durationMinutes: duration, busy: detail.busyPeriods ?? [])

        // Đặt và thanh toán (cổng giả lập của profile dev); listing tự nhận đơn nên chuyển CONFIRMED.
        let created = try await renter.createOrder(listingId: listing.id, startAt: start, durationMinutes: duration)
        let pay = try await renter.pay(orderId: created.id)
        XCTAssertEqual(pay.amount, created.amountToPay)
        try await renter.simulatePayment(intentId: pay.intentId)
        var current = try await renter.order(id: created.id)
        XCTAssertEqual(current.order.status, "CONFIRMED")
        XCTAssertNotNil(current.deposit)

        // Quy tắc hiển thị khớp trạng thái thật.
        let renterView = OrderActions(order: current.order, viewerId: renterId, handoverMode: detail.policy.handoverMode)
        XCTAssertTrue(renterView.canViewCredentials)
        XCTAssertTrue(renterView.canCancel)
        XCTAssertFalse(renterView.canPay)

        // Trước giờ thuê: backend từ chối xem credentials với lỗi có mã rõ ràng.
        do {
            _ = try await renter.credentials(orderId: created.id)
            XCTFail("chưa tới giờ thuê mà vẫn xem được credentials")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "CREDENTIAL_WINDOW_CLOSED")
        }

        // Chờ tới giờ bắt đầu; gọi job ngay thay vì đợi lịch chạy mỗi phút.
        let deadline = start.addingTimeInterval(150)
        while current.order.status != "IN_RENTAL" && Date() < deadline {
            try await Task.sleep(nanoseconds: 5_000_000_000)
            if Date() >= start {
                try await admin.send("POST", "dev/jobs/run-all")
            }
            current = try await renter.order(id: created.id)
        }
        XCTAssertEqual(current.order.status, "IN_RENTAL", "đơn không chuyển sang IN_RENTAL")

        // Trước lần xem credentials đầu tiên, hạn khiếu nại chưa bắt đầu nên chưa có nút khiếu nại.
        XCTAssertEqual(OrderActions(order: current.order, viewerId: renterId, handoverMode: nil).disputeTypes, [])

        let credentials = try await renter.credentials(orderId: created.id)
        XCTAssertEqual(credentials.username, "quick_player_01")
        XCTAssertFalse(credentials.password.isEmpty)

        // Đã xem credentials: hạn khiếu nại bắt đầu. Khiếu nại phải mở trước khi xác nhận bàn giao.
        current = try await renter.order(id: created.id)
        XCTAssertNotNil(current.order.complaintWindowEndsAt)
        let inRental = OrderActions(order: current.order, viewerId: renterId, handoverMode: detail.policy.handoverMode)
        XCTAssertEqual(inRental.disputeTypes, DisputeType.renterTypes)
        XCTAssertTrue(inRental.handoverEndsComplaints)
        try await renter.openDispute(orderId: created.id, type: DisputeType.renterNotAsDescribed,
                                     description: "Test tích hợp iOS: rank không đúng mô tả.",
                                     evidenceUrls: ["https://example.com/evidence-1.png"])
        let disputes = try await renter.disputes(orderId: created.id)
        let dispute = try XCTUnwrap(disputes.first)
        XCTAssertEqual(dispute.status, "OPEN")
        try await renter.addEvidence(disputeId: dispute.id, evidenceUrls: ["https://example.com/evidence-2.png"], note: "ảnh thứ hai")
        let afterEvidence = try await renter.disputes(orderId: created.id)
        let updated = try XCTUnwrap(afterEvidence.first)
        XCTAssertEqual(updated.evidence?.count, 2)

        // Xác nhận bàn giao: từ đây người thuê không mở thêm khiếu nại được nữa.
        try await renter.confirmHandover(orderId: created.id)
        current = try await renter.order(id: created.id)
        XCTAssertNotNil(current.order.handoverConfirmedAt)
        XCTAssertEqual(OrderActions(order: current.order, viewerId: renterId, handoverMode: nil).disputeTypes, [])
        do {
            try await renter.openDispute(orderId: created.id, type: DisputeType.renterLoginFailed,
                                         description: "Sau khi đã xác nhận bàn giao", evidenceUrls: [])
            XCTFail("backend vẫn cho khiếu nại sau khi xác nhận bàn giao")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "COMPLAINT_WINDOW_CLOSED")
        }

        // Chủ account thấy đơn trong danh sách cho thuê và chỉ được báo chiếm account khi đơn còn chạy.
        let ownerId = try await owner.me().id
        XCTAssertEqual(OrderActions(order: current.order, viewerId: ownerId, handoverMode: nil).disputeTypes,
                       [DisputeType.accountTakeover])
        let ownerOrders = try await owner.orders(role: .owner)
        XCTAssertTrue(ownerOrders.contains { $0.id == created.id })
    }

    // MARK: - Chủ account: tạo và sửa listing nháp

    func testOwnerCreatesAndEditsDraftListing() async throws {
        let owner = try await client("owner@rentgg.dev")
        let accounts = try await owner.gameAccounts()
        let account = try XCTUnwrap(accounts.first { $0.recoveryVerifiedAt != nil }, "owner có account đã xác minh")
        let tag = String(UUID().uuidString.prefix(6))

        var draft = ListingDraft(title: "Listing test iOS \(tag)", description: "Tạo bởi test tích hợp",
                                 pricePerHour: 35_000, depositAmount: 150_000, autoAccept: false,
                                 endMode: EndMode.graceForCurrentMatch)
        try await owner.createListing(gameAccountId: account.id, draft: draft)

        let mine = try await owner.myListings()
        let created = try XCTUnwrap(mine.first { $0.title == draft.title })
        XCTAssertEqual(created.status, "DRAFT")
        XCTAssertEqual(created.endMode, EndMode.graceForCurrentMatch)
        XCTAssertEqual(ListingActions(status: created.status).canSubmit, true)

        draft.title = "Listing test iOS \(tag) (đã sửa)"
        draft.pricePerHour = 40_000
        try await owner.updateListing(created, draft: draft)
        let afterEdit = try await owner.myListings()
        let edited = try XCTUnwrap(afterEdit.first { $0.id == created.id })
        XCTAssertEqual(edited.title, draft.title)
        XCTAssertEqual(edited.pricePerHour, 40_000)

        // Sửa lần nữa bằng bản cũ (version cũ) phải bị từ chối, không ghi đè im lặng.
        do {
            try await owner.updateListing(created, draft: draft)
            XCTFail("bản cũ vẫn ghi đè được")
        } catch let error as APIError {
            XCTAssertEqual(error.status, 409)
        }
    }
}
