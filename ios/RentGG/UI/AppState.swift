import Foundation
import RentGGCore
import SwiftUI

/// Trạng thái toàn app: phiên đăng nhập, người dùng hiện tại và thông báo ngắn (snackbar).
@MainActor
final class AppState: ObservableObject {
    let api: APIClient

    @Published private(set) var isLoggedIn: Bool
    @Published private(set) var me: Me?
    @Published var message: String?

    /// Bản Debug trỏ backend profile `dev`, có nút giả lập thanh toán.
    let isDevBuild: Bool = {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }()

    init() {
        let raw = Bundle.main.object(forInfoDictionaryKey: "APIBaseURL") as? String
        let baseURL = raw.flatMap(URL.init(string:)) ?? URL(string: "http://localhost:8080/api/v1/")!
        api = APIClient(baseURL: baseURL, tokens: TokenStore())
        isLoggedIn = api.hasSession
        api.onSessionExpired = { [weak self] in
            Task { @MainActor in self?.expireSession() }
        }
    }

    func didLogin() async {
        isLoggedIn = true
        await refreshMe()
    }

    /// Tải lại hồ sơ; trả `false` khi lỗi (giữ hồ sơ cũ nếu đã có).
    @discardableResult
    func refreshMe() async -> Bool {
        guard let fresh = try? await api.me() else { return false }
        me = fresh
        return true
    }

    func logout() {
        api.logout()
        me = nil
        isLoggedIn = false
    }

    func expireSession() {
        guard isLoggedIn else { return }
        api.logout()
        me = nil
        isLoggedIn = false
        show("Phiên đăng nhập đã hết hạn.")
    }

    func show(_ text: String) {
        message = text
    }

    /// Chạy một thao tác ghi; báo `success` hoặc lỗi qua snackbar. Trả `true` khi thành công.
    @discardableResult
    func perform(success: String? = nil, _ operation: () async throws -> Void) async -> Bool {
        do {
            try await operation()
            if let success { show(success) }
            return true
        } catch {
            show(errorText(error))
            return false
        }
    }
}

/// Trạng thái tải dữ liệu của một màn hình.
enum LoadState<T> {
    case loading
    case error(String)
    case ready(T)

    var value: T? {
        if case .ready(let v) = self { return v }
        return nil
    }
}

func errorText(_ error: Error) -> String {
    if let api = error as? APIError { return api.message }
    return "Có lỗi xảy ra. Vui lòng thử lại."
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
