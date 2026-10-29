import XCTest
@testable import RentGGCore

final class RulesTests: XCTestCase {
    private func order(_ status: String, handoverConfirmedAt: String? = nil, complaintWindowEndsAt: String? = nil) throws -> Order {
        let handover = handoverConfirmedAt.map { "\"\($0)\"" } ?? "null"
        let window = complaintWindowEndsAt.map { "\"\($0)\"" } ?? "null"
        return try JSONDecoder().decode(Order.self, from: Data("""
        {"id":"o1","listingId":"l1","gameId":"g1","renterId":"renter","ownerId":"owner","status":"\(status)",
         "startAt":"2026-10-27T01:00:00Z","scheduledEndAt":"2026-10-27T03:00:00Z",
         "handoverConfirmedAt":\(handover),"complaintWindowEndsAt":\(window)}
        """.utf8))
    }

    /// Giữa giờ thuê của đơn mẫu (01:00–03:00 ngày 27/10/2026 UTC).
    private let duringRental = Date(timeIntervalSince1970: 1_793_068_200) // 02:30Z

    func testRenterPendingPayment() throws {
        let a = OrderActions(order: try order("PENDING_PAYMENT"), viewerId: "renter", handoverMode: "STATIC_CREDENTIAL")
        XCTAssertTrue(a.canPay)
        XCTAssertTrue(a.canCancel)
        XCTAssertFalse(a.canApprove)
        XCTAssertFalse(a.canViewCredentials)
        XCTAssertEqual(a.disputeTypes, [])
    }

    func testOwnerAwaitingApproval() throws {
        let a = OrderActions(order: try order("AWAITING_OWNER_APPROVAL"), viewerId: "owner", handoverMode: nil)
        XCTAssertTrue(a.canApprove)
        XCTAssertTrue(a.canCancel)
        XCTAssertFalse(a.canPay)
    }

    func testConfirmedHandoverAndCredentials() throws {
        let fresh = OrderActions(order: try order("CONFIRMED"), viewerId: "renter", handoverMode: "STATIC_CREDENTIAL")
        XCTAssertTrue(fresh.canConfirmHandover)
        XCTAssertTrue(fresh.canViewCredentials)
        XCTAssertFalse(fresh.canRequestOtp)

        let done = OrderActions(order: try order("CONFIRMED", handoverConfirmedAt: "2026-10-27T01:05:00Z"),
                                viewerId: "renter", handoverMode: "STATIC_CREDENTIAL")
        XCTAssertFalse(done.canConfirmHandover)
    }

    func testOtpOnlyForOtpGames() throws {
        let renter = OrderActions(order: try order("IN_RENTAL"), viewerId: "renter", handoverMode: "OWNER_ASSISTED_OTP")
        let owner = OrderActions(order: try order("IN_RENTAL"), viewerId: "owner", handoverMode: "OWNER_ASSISTED_OTP")
        XCTAssertTrue(renter.canRequestOtp)
        XCTAssertFalse(renter.canSendOtp)
        XCTAssertTrue(owner.canSendOtp)
        XCTAssertFalse(owner.canRequestOtp)
        XCTAssertFalse(owner.canCancel)
    }

    func testRenterComplaintsNeedOpenWindowAndNoHandover() throws {
        let open = try order("IN_RENTAL", complaintWindowEndsAt: "2026-10-27T02:45:00.123456Z")
        let actions = OrderActions(order: open, viewerId: "renter", handoverMode: nil, now: duringRental)
        XCTAssertEqual(actions.disputeTypes, DisputeType.renterTypes)
        XCTAssertTrue(actions.handoverEndsComplaints)

        // Chưa xem credentials lần nào: hạn khiếu nại chưa bắt đầu.
        XCTAssertEqual(OrderActions(order: try order("IN_RENTAL"), viewerId: "renter", handoverMode: nil, now: duringRental).disputeTypes, [])
        // Đã quá hạn.
        let expired = try order("IN_RENTAL", complaintWindowEndsAt: "2026-10-27T02:00:00Z")
        XCTAssertEqual(OrderActions(order: expired, viewerId: "renter", handoverMode: nil, now: duringRental).disputeTypes, [])
        // Đã xác nhận bàn giao.
        let confirmed = try order("IN_RENTAL", handoverConfirmedAt: "2026-10-27T01:10:00Z", complaintWindowEndsAt: "2026-10-27T02:45:00Z")
        let afterHandover = OrderActions(order: confirmed, viewerId: "renter", handoverMode: nil, now: duringRental)
        XCTAssertEqual(afterHandover.disputeTypes, [])
        XCTAssertFalse(afterHandover.handoverEndsComplaints)
        // Đơn đã kết thúc.
        XCTAssertEqual(OrderActions(order: try order("ENDED", complaintWindowEndsAt: "2026-10-27T02:45:00Z"),
                                    viewerId: "renter", handoverMode: nil, now: duringRental).disputeTypes, [])
    }

    func testOwnerDisputeTypesByStatus() throws {
        XCTAssertEqual(OrderActions(order: try order("ENDED"), viewerId: "owner", handoverMode: nil).disputeTypes,
                       DisputeType.ownerTypes)
        XCTAssertEqual(OrderActions(order: try order("IN_RENTAL"), viewerId: "owner", handoverMode: nil).disputeTypes,
                       [DisputeType.accountTakeover])
        XCTAssertEqual(OrderActions(order: try order("CONFIRMED"), viewerId: "owner", handoverMode: nil).disputeTypes, [])
        XCTAssertEqual(OrderActions(order: try order("IN_RENTAL"), viewerId: "stranger", handoverMode: nil).disputeTypes, [])
    }

    func testUnknownViewerSeesNothing() throws {
        let a = OrderActions(order: try order("PENDING_PAYMENT"), viewerId: nil, handoverMode: nil)
        XCTAssertFalse(a.isRenter || a.isOwner || a.canPay || a.canCancel)
    }

    func testListingActions() {
        XCTAssertEqual(ListingActions(status: "DRAFT"), ListingActions(status: "PAUSED"))
        XCTAssertTrue(ListingActions(status: "DRAFT").canSubmit)
        XCTAssertTrue(ListingActions(status: "ACTIVE").canPause)
        XCTAssertFalse(ListingActions(status: "ACTIVE").canSubmit)
        XCTAssertFalse(ListingActions(status: "SUSPENDED").canEdit)
        XCTAssertTrue(ListingActions(status: "REJECTED").canEdit)
    }

    func testDateValidation() {
        XCTAssertTrue(Validation.isValidDate("2000-02-29"))
        XCTAssertFalse(Validation.isValidDate("2001-02-29"))
        XCTAssertFalse(Validation.isValidDate("2001-13-01"))
        XCTAssertFalse(Validation.isValidDate("2001-1-01"))
        XCTAssertFalse(Validation.isValidDate("01/01/2001"))
    }

    func testAgeCheck() throws {
        let today = try XCTUnwrap(parseISODate("2026-10-26T00:00:00Z"))
        XCTAssertTrue(Validation.isAtLeast(age: 18, dateOfBirth: "2008-10-26", today: today))
        XCTAssertFalse(Validation.isAtLeast(age: 18, dateOfBirth: "2008-10-27", today: today))
    }

    func testEmailAndBankAccount() {
        XCTAssertTrue(Validation.isValidEmail("renter@rentgg.dev"))
        XCTAssertFalse(Validation.isValidEmail("renter@rentgg"))
        XCTAssertFalse(Validation.isValidEmail("a b@c.d"))
        XCTAssertTrue(Validation.isValidBankAccount("0123456789"))
        XCTAssertFalse(Validation.isValidBankAccount("12345"))
        XCTAssertFalse(Validation.isValidBankAccount("12345a7"))
    }

    func testEvidenceUrls() {
        let urls = Validation.parseUrls("  https://a.example/1.png \n\n http://b.example/2.png\nftp://x\nhttps://")
        XCTAssertEqual(urls, ["https://a.example/1.png", "http://b.example/2.png", "ftp://x", "https://"])
        XCTAssertEqual(Validation.invalidEvidenceUrls(urls), ["http://b.example/2.png", "ftp://x", "https://"])
    }

    func testDurationOptions() {
        XCTAssertEqual(Booking.durationOptions(min: 60, max: 180), [60, 90, 120, 150, 180])
        XCTAssertEqual(Booking.durationOptions(min: 60, max: 100), [60, 90, 100])
        XCTAssertEqual(Booking.durationOptions(min: 15, max: 30), [30])
    }

    func testOverlapWithBusyPeriods() throws {
        let busy = [BusyPeriod(startAt: "2026-10-27T01:00:00Z", endAt: "2026-10-27T03:00:00Z")]
        let before = try XCTUnwrap(parseISODate("2026-10-26T23:00:00Z"))
        let touching = try XCTUnwrap(parseISODate("2026-10-27T03:00:00Z"))
        let inside = try XCTUnwrap(parseISODate("2026-10-27T02:00:00Z"))
        XCTAssertNil(Booking.overlapping(start: before, durationMinutes: 120, busy: busy))
        XCTAssertNil(Booking.overlapping(start: touching, durationMinutes: 60, busy: busy))
        XCTAssertNotNil(Booking.overlapping(start: before, durationMinutes: 150, busy: busy))
        XCTAssertNotNil(Booking.overlapping(start: inside, durationMinutes: 30, busy: busy))
    }

    func testEstimatedRentAndDefaultStart() throws {
        XCTAssertEqual(Booking.estimatedRent(pricePerHour: 30_000, durationMinutes: 90), 45_000)
        XCTAssertEqual(Booking.estimatedRent(pricePerHour: 10_001, durationMinutes: 30), 5_001)
        let now = try XCTUnwrap(parseISODate("2026-10-26T08:10:00Z"))
        XCTAssertEqual(isoString(Booking.defaultStart(now: now)), "2026-10-26T09:30:00Z")
    }
}
