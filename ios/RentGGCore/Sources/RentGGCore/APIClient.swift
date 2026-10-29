import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Lỗi từ backend (`{code, message, details}`) hoặc lỗi mạng (status = 0).
public struct APIError: LocalizedError, Sendable, Equatable {
    public let status: Int
    public let code: String
    public let message: String

    public init(status: Int, code: String, message: String) {
        self.status = status
        self.code = code
        self.message = message
    }

    public var errorDescription: String? { message }
}

/// Lớp gửi request, tách ra để test thay bằng bản giả.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (status: Int, body: Data)
}

public struct URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(timeout: TimeInterval = 20) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout + 10
        session = URLSession(configuration: config)
    }

    public func send(_ request: URLRequest) async throws -> (status: Int, body: Data) {
        // Dùng dataTask + continuation thay cho `data(for:)` để chạy được cả trên Linux.
        try await withCheckedThrowingContinuation { continuation in
            let task = session.dataTask(with: request) { data, response, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    continuation.resume(returning: (status, data ?? Data()))
                }
            }
            task.resume()
        }
    }
}

/// Đảm bảo chỉ một lần refresh chạy tại một thời điểm; các request song song chờ chung kết quả.
actor RefreshGate {
    private var inFlight: Task<Bool, Never>?

    func run(_ work: @escaping @Sendable () async -> Bool) async -> Bool {
        if let inFlight { return await inFlight.value }
        let task = Task { await work() }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }
}

private struct ErrorBody: Decodable {
    let code: String?
    let message: String?
}

/// Client HTTP mỏng cho API /api/v1. Tự gắn Bearer, tự refresh một lần khi 401
/// (dùng chung giữa các request song song), và gọi `onSessionExpired` khi refresh thất bại.
/// Các endpoint cụ thể nằm ở `Endpoints.swift`.
public final class APIClient: @unchecked Sendable {
    public var onSessionExpired: (@Sendable () -> Void)?

    public let baseURL: URL
    let tokens: TokenStore
    private let transport: HTTPTransport
    private let refreshGate = RefreshGate()
    let decoder = JSONDecoder()
    let encoder = JSONEncoder()

    public init(baseURL: URL, tokens: TokenStore, transport: HTTPTransport = URLSessionTransport()) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.transport = transport
    }

    // MARK: - Lõi

    /// Gửi request và trả body khi status 2xx; ngược lại ném `APIError`.
    /// `idempotent` sinh một `Idempotency-Key` mới cho hành động này và giữ nguyên khi retry sau refresh.
    @discardableResult
    func send(
        _ method: String,
        _ path: String,
        query: [String: String] = [:],
        body: Data? = nil,
        idempotent: Bool = false,
        anonymous: Bool = false
    ) async throws -> Data {
        let idKey = idempotent ? UUID().uuidString : nil
        let sentToken = tokens.accessToken
        var (status, data) = try await perform(method, path, query: query, body: body, idempotencyKey: idKey, anonymous: anonymous)

        if status == 401, !anonymous, tokens.hasSession {
            // Token đã được request khác làm mới trong lúc chờ: chỉ cần gửi lại.
            var refreshed = tokens.accessToken != sentToken
            if !refreshed {
                refreshed = await refreshGate.run { [weak self] in await self?.refresh() ?? false }
            }
            if refreshed {
                (status, data) = try await perform(method, path, query: query, body: body, idempotencyKey: idKey, anonymous: anonymous)
            } else {
                onSessionExpired?()
            }
        }

        guard (200..<300).contains(status) else {
            let err = try? decoder.decode(ErrorBody.self, from: data)
            throw APIError(status: status, code: err?.code ?? "UNKNOWN", message: err?.message ?? "Lỗi máy chủ (\(status)).")
        }
        return data
    }

    func get<T: Decodable>(_ path: String, query: [String: String] = [:]) async throws -> T {
        try decode(T.self, from: try await send("GET", path, query: query))
    }

    func encode<B: Encodable>(_ body: B) throws -> Data {
        try encoder.encode(body)
    }

    func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError(status: 200, code: "INVALID_RESPONSE", message: "Phản hồi từ máy chủ không hợp lệ.")
        }
    }

    func makeRequest(
        _ method: String,
        _ path: String,
        query: [String: String],
        body: Data?,
        idempotencyKey: String?,
        anonymous: Bool
    ) throws -> URLRequest {
        guard var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw APIError(status: 0, code: "INVALID_URL", message: "Địa chỉ máy chủ không hợp lệ.")
        }
        if !query.isEmpty {
            comps.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else {
            throw APIError(status: 0, code: "INVALID_URL", message: "Địa chỉ máy chủ không hợp lệ.")
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.httpBody = body
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        if let idempotencyKey {
            req.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key")
        }
        if !anonymous, let token = tokens.accessToken {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    private func perform(
        _ method: String,
        _ path: String,
        query: [String: String],
        body: Data?,
        idempotencyKey: String?,
        anonymous: Bool
    ) async throws -> (Int, Data) {
        let req = try makeRequest(method, path, query: query, body: body, idempotencyKey: idempotencyKey, anonymous: anonymous)
        do {
            let result = try await transport.send(req)
            return (result.status, result.body)
        } catch {
            throw APIError(status: 0, code: "NETWORK", message: "Không kết nối được máy chủ. Kiểm tra mạng và thử lại.")
        }
    }

    /// Trả về `true` khi đổi được token mới. Chỉ xóa phiên khi máy chủ từ chối refresh token,
    /// còn lỗi mạng thì giữ phiên để lần sau thử lại.
    private func refresh() async -> Bool {
        guard let current = tokens.refreshToken,
              let body = try? encoder.encode(["refreshToken": current]),
              let result = try? await perform("POST", "auth/refresh", query: [:], body: body, idempotencyKey: nil, anonymous: true)
        else { return false }

        let (status, data) = result
        guard (200..<300).contains(status) else {
            tokens.clear()
            return false
        }
        guard let t = try? decoder.decode(TokenResponse.self, from: data) else { return false }
        tokens.save(access: t.accessToken, refresh: t.refreshToken)
        return true
    }
}
