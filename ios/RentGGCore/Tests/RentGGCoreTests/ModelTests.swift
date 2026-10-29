import XCTest
@testable import RentGGCore

/// Giải mã JSON có hình dạng giống backend thật (thêm trường lạ, thiếu trường optional, null).
final class ModelTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    func testListingDetailWithAttributesAndBusyPeriods() throws {
        let detail = try decode(ListingDetail.self, """
        {
          "listing": {
            "id": "l1", "gameAccountId": "a1", "ownerId": "u2", "gameId": "g1",
            "title": "Acc rank Cao Thủ", "description": null,
            "attributes": {"rank": "Cao Thủ", "skins": 120, "verified": true, "heroes": ["A", "B"]},
            "pricePerHour": 30000, "depositAmount": 200000, "autoAccept": true,
            "endMode": "FORCE_AT_END", "status": "ACTIVE", "reviewNote": null, "version": 3,
            "createdAt": "2026-10-26T08:00:00.123456Z"
          },
          "policy": {
            "handoverMode": "STATIC_CREDENTIAL", "depositHoldHours": 24, "complaintWindowMinutes": 15,
            "minDurationMinutes": 60, "maxDurationMinutes": 1440, "graceMinutes": 10,
            "renterFreeCancelMinutes": 30, "lateCancelRefundBps": 5000, "minAge": 18
          },
          "busyPeriods": [{"startAt": "2026-10-27T01:00:00Z", "endAt": "2026-10-27T03:00:00Z"}],
          "bookable": true
        }
        """)
        XCTAssertEqual(detail.listing.attributes?["rank"]?.display, "Cao Thủ")
        XCTAssertEqual(detail.listing.attributes?["skins"]?.display, "120")
        XCTAssertEqual(detail.listing.attributes?["verified"]?.display, "Có")
        XCTAssertEqual(detail.listing.attributes?["heroes"]?.display, "A, B")
        XCTAssertNil(detail.listing.description)
        XCTAssertEqual(detail.policy.minDurationMinutes, 60)
        XCTAssertEqual(detail.busyPeriods?.count, 1)
        XCTAssertEqual(detail.bookable, true)
    }

    func testOrderDetailToleratesUnknownAndMissingFields() throws {
        let detail = try decode(OrderDetail.self, """
        {
          "order": {
            "id": "o1", "listingId": "l1", "gameAccountId": "a1", "gameId": "g1",
            "renterId": "u1", "ownerId": "u2", "status": "PENDING_PAYMENT",
            "startAt": "2026-10-27T01:00:00Z", "scheduledEndAt": "2026-10-27T03:00:00Z",
            "blockEndAt": "2026-10-27T03:30:00Z", "rentAmount": 60000, "amountToPay": 260000,
            "someFutureField": {"nested": [1, 2, 3]}
          },
          "deposit": null,
          "refunds": []
        }
        """)
        XCTAssertEqual(detail.order.amountToPay, 260_000)
        XCTAssertNil(detail.order.depositAmount)
        XCTAssertNil(detail.deposit)
    }

    func testWithdrawalBankAndDisputeEvidence() throws {
        let w = try decode(Withdrawal.self, """
        {"id": "w1", "ownerId": "u2", "amount": 500000, "status": "REQUESTED",
         "bank": {"bankName": "VCB", "bankAccountNumber": "****6789", "bankAccountName": "NGUYEN VAN A"},
         "bankRef": null, "failureReason": null, "processedAt": null, "createdAt": "2026-10-26T08:00:00Z"}
        """)
        XCTAssertEqual(w.bank?["bankAccountNumber"]?.stringValue, "****6789")

        let d = try decode(Dispute.self, """
        {"id": "d1", "orderId": "o1", "openedBy": "u1", "type": "RENTER_LOGIN_FAILED", "status": "OPEN",
         "description": "Sai mật khẩu", "evidence": [{"url": "https://img.example/1.png", "note": "ảnh"}],
         "deadlineAt": "2026-10-28T08:00:00Z", "depositDeduction": 0, "rentRefund": 0,
         "decidedBy": null, "decidedAt": null, "reasonCode": null, "decisionNote": null,
         "createdAt": "2026-10-26T08:00:00Z"}
        """)
        XCTAssertEqual(d.evidence?.first?["url"]?.stringValue, "https://img.example/1.png")
    }

    func testPageResponse() throws {
        let page = try decode(PageResponse<NotificationItem>.self, """
        {"content": [{"id": "n1", "type": "ORDER_PAID", "title": "Đơn đã thanh toán", "body": "",
                      "data": {"orderId": "o1"}, "readAt": null, "createdAt": "2026-10-26T08:00:00Z"}],
         "page": 0, "size": 1, "totalElements": 7, "totalPages": 7}
        """)
        XCTAssertEqual(page.totalElements, 7)
        XCTAssertEqual(page.content.first?.title, "Đơn đã thanh toán")
    }
}
