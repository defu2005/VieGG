import Foundation
#if canImport(Security)
import Security
#endif

/// Nơi lưu bí mật dạng byte. Trên iOS là Keychain; trong test (và trên Linux) là bộ nhớ.
public protocol SecureStore: AnyObject, Sendable {
    func read() -> Data?
    func write(_ data: Data)
    func delete()
}

public final class InMemorySecureStore: SecureStore, @unchecked Sendable {
    private var data: Data?
    private let lock = NSLock()

    public init(data: Data? = nil) {
        self.data = data
    }

    public func read() -> Data? { locked(lock) { data } }
    public func write(_ data: Data) { locked(lock) { self.data = data } }
    public func delete() { locked(lock) { data = nil } }
}

#if canImport(Security)
/// Keychain, chỉ đọc được khi thiết bị đã mở khóa và không đồng bộ sang thiết bị khác
/// (tương đương EncryptedSharedPreferences bên Android).
public final class KeychainStore: SecureStore, @unchecked Sendable {
    private let service: String
    private let account: String

    public init(service: String = "com.rentgg.app.session", account: String = "tokens") {
        self.service = service
        self.account = account
    }

    public func read() -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    public func write(_ data: Data) {
        var query = baseQuery()
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    public func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
#endif

/// Cặp access/refresh token, có cache trong bộ nhớ để không đọc Keychain ở mỗi request.
public final class TokenStore: @unchecked Sendable {
    private struct Stored: Codable {
        let access: String
        let refresh: String
    }

    private let store: SecureStore
    private let lock = NSLock()
    private var cached: Stored?

    public init(store: SecureStore) {
        self.store = store
        cached = store.read().flatMap { try? JSONDecoder().decode(Stored.self, from: $0) }
    }

    /// Keychain trên nền tảng Apple, bộ nhớ ở nơi khác.
    public convenience init() {
        #if canImport(Security)
        self.init(store: KeychainStore())
        #else
        self.init(store: InMemorySecureStore())
        #endif
    }

    public var accessToken: String? { locked(lock) { cached?.access } }
    public var refreshToken: String? { locked(lock) { cached?.refresh } }
    public var hasSession: Bool { locked(lock) { cached != nil } }

    public func save(access: String, refresh: String) {
        locked(lock) {
            let stored = Stored(access: access, refresh: refresh)
            cached = stored
            if let data = try? JSONEncoder().encode(stored) {
                store.write(data)
            }
        }
    }

    public func clear() {
        locked(lock) {
            cached = nil
            store.delete()
        }
    }
}

/// `NSLock.withLock` chưa chắc có trên mọi bản Foundation cho Linux, nên tự viết.
func locked<T>(_ lock: NSLock, _ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
}
