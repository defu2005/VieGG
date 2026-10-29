import XCTest
@testable import RentGGCore

final class FormatTests: XCTestCase {
    func testVndGroupsThousandsWithDots() {
        XCTAssertEqual(vnd(0), "0đ")
        XCTAssertEqual(vnd(999), "999đ")
        XCTAssertEqual(vnd(30_000), "30.000đ")
        XCTAssertEqual(vnd(1_234_567), "1.234.567đ")
        XCTAssertEqual(vnd(-200_000), "-200.000đ")
    }

    func testParseIsoWithoutFraction() throws {
        let date = try XCTUnwrap(parseISODate("2026-10-26T08:00:00Z"))
        XCTAssertEqual(date.timeIntervalSince1970, 1_793_001_600)
    }

    /// Postgres/Jackson trả micro giây; trước đây hiển thị thành "—".
    func testParseIsoWithMicroAndNanoSeconds() throws {
        let micro = try XCTUnwrap(parseISODate("2026-10-26T08:00:00.123456Z"))
        let nano = try XCTUnwrap(parseISODate("2026-10-26T08:00:00.123456789Z"))
        let milli = try XCTUnwrap(parseISODate("2026-10-26T08:00:00.1Z"))
        XCTAssertEqual(micro.timeIntervalSince1970, 1_793_001_600.123, accuracy: 0.0005)
        XCTAssertEqual(nano.timeIntervalSince1970, 1_793_001_600.123, accuracy: 0.0005)
        XCTAssertEqual(milli.timeIntervalSince1970, 1_793_001_600.1, accuracy: 0.0005)
    }

    func testParseIsoWithOffset() throws {
        let date = try XCTUnwrap(parseISODate("2026-10-26T15:00:00+07:00"))
        XCTAssertEqual(date.timeIntervalSince1970, 1_793_001_600)
    }

    func testParseIsoRejectsGarbage() {
        XCTAssertNil(parseISODate(""))
        XCTAssertNil(parseISODate("2026-10-26"))
        XCTAssertNil(parseISODate("not a date"))
    }

    func testIsoStringRoundTrips() throws {
        let date = Date(timeIntervalSince1970: 1_793_001_600)
        XCTAssertEqual(isoString(date), "2026-10-26T08:00:00Z")
        XCTAssertEqual(parseISODate(isoString(date)), date)
    }

    func testFmtTimeUsesGivenTimeZone() throws {
        let hcm = try XCTUnwrap(TimeZone(identifier: "Asia/Ho_Chi_Minh"))
        XCTAssertEqual(fmtTime("2026-10-26T08:00:00.654321Z", timeZone: hcm), "15:00 26/10/2026")
        XCTAssertEqual(fmtTime(nil), "—")
        XCTAssertEqual(fmtTime("oops"), "—")
    }

    func testDurationText() {
        XCTAssertEqual(durationText(minutes: 30), "30 phút")
        XCTAssertEqual(durationText(minutes: 120), "2 giờ")
        XCTAssertEqual(durationText(minutes: 90), "1 giờ 30 phút")
    }

    func testStatusLabels() {
        XCTAssertEqual(orderStatusLabel("IN_RENTAL"), StatusLabel(text: "Đang thuê", tone: .success))
        XCTAssertEqual(orderStatusLabel("SOMETHING_NEW").text, "SOMETHING_NEW")
        XCTAssertEqual(listingStatusLabel(nil).text, "—")
        XCTAssertEqual(withdrawalStatusLabel("REQUESTED").text, "Chờ chuyển khoản")
        XCTAssertEqual(depositStatusLabel("POST_RENTAL_HOLD").text, "Giữ sau thuê")
        XCTAssertEqual(kycText(nil), "Chưa nộp")
        XCTAssertEqual(disputeTypeText(DisputeType.renterLoginFailed), "Không đăng nhập được")
    }
}
