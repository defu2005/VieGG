import XCTest
@testable import RentGGCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Transport giả: ghi lại request và trả lời theo hàm `respond`.
final class MockTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    var respond: @Sendable (URLRequest) throws -> (Int, String)

    init(respond: @escaping @Sendable (URLRequest) throws -> (Int, String)) {
        self.respond = respond
    }

    var requests: [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return _requests
    }

    func send(_ request: URLRequest) async throws -> (status: Int, body: Data) {
        lock.lock()
        _requests.append(request)
        lock.unlock()
        // Nhường luồng để các request song song thật sự chồng lên nhau.
        try await Task.sleep(nanoseconds: 5_000_000)
        let (status, body) = try respond(request)
        return (status, Data(body.utf8))
    }
}

/// Đếm số lần gọi một cách an toàn giữa các luồng.
final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func increment() { lock.lock(); value += 1; lock.unlock() }
    var count: Int { lock.lock(); defer { lock.unlock() }; return value }
}

final class APIClientTests: XCTestCase {
    private let base = URL(string: "http://localhost:8080/api/v1/")!

    private func path(_ r: URLRequest) -> String { r.url?.path ?? "" }

    private func bodyJSON(_ r: URLRequest) -> [String: Any] {
        guard let data = r.httpBody, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }

    func testLoginIsAnonymousAndSavesTokens() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        let transport = MockTransport { _ in (200, #"{"accessToken":"A1","refreshToken":"R1","tokenType":"Bearer"}"#) }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        try await api.login(email: "renter@rentgg.dev", password: "secret")

        let req = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(path(req), "/api/v1/auth/login")
        XCTAssertEqual(req.httpMethod, "POST")
        XCTAssertNil(req.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(bodyJSON(req)["email"] as? String, "renter@rentgg.dev")
        XCTAssertEqual(tokens.accessToken, "A1")
        XCTAssertEqual(tokens.refreshToken, "R1")
    }

    func testTokensPersistThroughStore() {
        let store = InMemorySecureStore()
        TokenStore(store: store).save(access: "A", refresh: "R")
        let reopened = TokenStore(store: store)
        XCTAssertEqual(reopened.accessToken, "A")
        reopened.clear()
        XCTAssertFalse(TokenStore(store: store).hasSession)
    }

    func testAuthorizedGetSendsBearerAndQuery() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "A1", refresh: "R1")
        let transport = MockTransport { _ in (200, #"{"content":[],"page":0,"size":50,"totalElements":0,"totalPages":0}"#) }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        _ = try await api.orders(role: .owner)

        let req = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer A1")
        let items = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertTrue(items.contains(URLQueryItem(name: "role", value: "OWNER")))
        XCTAssertTrue(items.contains(URLQueryItem(name: "size", value: "50")))
    }

    func testCreateOrderSendsIdempotencyKeyAndIsoStart() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "A1", refresh: "R1")
        let transport = MockTransport { _ in
            (201, #"{"id":"o1","listingId":"l1","gameId":"g1","renterId":"u1","ownerId":"u2","status":"PENDING_PAYMENT","startAt":"2026-10-27T01:00:00Z","scheduledEndAt":"2026-10-27T03:00:00Z"}"#)
        }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        let order = try await api.createOrder(listingId: "l1", startAt: Date(timeIntervalSince1970: 1_793_062_800), durationMinutes: 120)

        XCTAssertEqual(order.id, "o1")
        let req = try XCTUnwrap(transport.requests.first)
        XCTAssertNotNil(UUID(uuidString: req.value(forHTTPHeaderField: "Idempotency-Key") ?? ""))
        XCTAssertEqual(bodyJSON(req)["startAt"] as? String, "2026-10-27T01:00:00Z")
        XCTAssertEqual(bodyJSON(req)["durationMinutes"] as? Int, 120)
    }

    func testNilOptionalsAreOmittedFromBody() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "A1", refresh: "R1")
        let transport = MockTransport { _ in (200, "{}") }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        try await api.rotateCredentials(accountId: "a1", newUsername: nil, newPassword: "p", loggedOutAllDevices: true)

        let json = bodyJSON(try XCTUnwrap(transport.requests.first))
        XCTAssertNil(json["newUsername"])
        XCTAssertEqual(json["loggedOutAllDevices"] as? Bool, true)
    }

    func testRefreshesOnceOn401AndRetriesWithSameIdempotencyKey() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "OLD", refresh: "R1")
        let transport = MockTransport { req in
            if req.url!.path.hasSuffix("auth/refresh") {
                return (200, #"{"accessToken":"NEW","refreshToken":"R2"}"#)
            }
            if req.value(forHTTPHeaderField: "Authorization") == "Bearer OLD" {
                return (401, #"{"code":"UNAUTHORIZED","message":"expired"}"#)
            }
            return (200, #"{"intentId":"i1","amount":260000,"status":"CREATED","checkoutUrl":null}"#)
        }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        let pay = try await api.pay(orderId: "o1")

        XCTAssertEqual(pay.intentId, "i1")
        XCTAssertEqual(tokens.accessToken, "NEW")
        XCTAssertEqual(tokens.refreshToken, "R2")
        let payRequests = transport.requests.filter { $0.url!.path.hasSuffix("/pay") }
        XCTAssertEqual(payRequests.count, 2)
        XCTAssertEqual(payRequests[0].value(forHTTPHeaderField: "Idempotency-Key"),
                       payRequests[1].value(forHTTPHeaderField: "Idempotency-Key"))
        XCTAssertEqual(payRequests[1].value(forHTTPHeaderField: "Authorization"), "Bearer NEW")
        let refreshBody = bodyJSON(try XCTUnwrap(transport.requests.first { $0.url!.path.hasSuffix("auth/refresh") }))
        XCTAssertEqual(refreshBody["refreshToken"] as? String, "R1")
    }

    func testParallel401sShareOneRefresh() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "OLD", refresh: "R1")
        let transport = MockTransport { req in
            if req.url!.path.hasSuffix("auth/refresh") {
                return (200, #"{"accessToken":"NEW","refreshToken":"R2"}"#)
            }
            if req.value(forHTTPHeaderField: "Authorization") == "Bearer OLD" { return (401, "") }
            return (200, #"{"id":"u1","email":"a@b.c","displayName":"A"}"#)
        }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        async let a = api.me()
        async let b = api.me()
        async let c = api.me()
        let results = try await [a, b, c]

        XCTAssertEqual(results.map(\.id), ["u1", "u1", "u1"])
        XCTAssertEqual(transport.requests.filter { $0.url!.path.hasSuffix("auth/refresh") }.count, 1)
    }

    func testRejectedRefreshClearsSessionAndNotifies() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "OLD", refresh: "R1")
        let expired = Counter()
        let transport = MockTransport { req in
            req.url!.path.hasSuffix("auth/refresh") ? (401, "") : (401, #"{"code":"UNAUTHORIZED","message":"Hết phiên"}"#)
        }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)
        api.onSessionExpired = { expired.increment() }

        do {
            _ = try await api.me()
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error.status, 401)
            XCTAssertEqual(error.message, "Hết phiên")
        }
        XCTAssertFalse(tokens.hasSession)
        XCTAssertEqual(expired.count, 1)
    }

    func testAnonymous401DoesNotRefresh() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        let transport = MockTransport { _ in (401, #"{"code":"BAD_CREDENTIALS","message":"Sai email hoặc mật khẩu."}"#) }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)

        do {
            try await api.login(email: "x@y.z", password: "bad")
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "BAD_CREDENTIALS")
            XCTAssertEqual(error.message, "Sai email hoặc mật khẩu.")
        }
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testErrorMappingForNonJsonAndNetworkFailures() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "A", refresh: "R")

        let gateway = APIClient(baseURL: base, tokens: tokens, transport: MockTransport { _ in (502, "<html>Bad Gateway</html>") })
        do {
            _ = try await gateway.balances()
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error, APIError(status: 502, code: "UNKNOWN", message: "Lỗi máy chủ (502)."))
        }

        struct Offline: Error {}
        let offline = APIClient(baseURL: base, tokens: tokens, transport: MockTransport { _ in throw Offline() })
        do {
            _ = try await offline.balances()
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "NETWORK")
            XCTAssertEqual(error.status, 0)
        }

        let garbage = APIClient(baseURL: base, tokens: tokens, transport: MockTransport { _ in (200, "not json") })
        do {
            _ = try await garbage.balances()
            XCTFail("phải ném lỗi")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "INVALID_RESPONSE")
        }
    }

    func testListingUpdateSendsVersionAndOmitsAccount() async throws {
        let tokens = TokenStore(store: InMemorySecureStore())
        tokens.save(access: "A", refresh: "R")
        let transport = MockTransport { _ in (200, "{}") }
        let api = APIClient(baseURL: base, tokens: tokens, transport: transport)
        let listing = try JSONDecoder().decode(Listing.self, from: Data("""
        {"id":"l1","gameAccountId":"a1","ownerId":"u2","gameId":"g1","title":"Cũ","pricePerHour":1,"depositAmount":2,"version":7}
        """.utf8))
        var draft = ListingDraft(listing: listing)
        draft.title = "  Mới  "

        try await api.updateListing(listing, draft: draft)

        let req = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(req.httpMethod, "PUT")
        XCTAssertEqual(path(req), "/api/v1/listings/l1")
        let json = bodyJSON(req)
        XCTAssertEqual(json["version"] as? Int, 7)
        XCTAssertEqual(json["title"] as? String, "Mới")
        XCTAssertNil(json["gameAccountId"])
        XCTAssertNil(json["description"])
    }
}
