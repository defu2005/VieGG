import SwiftUI

@main
struct RentGGApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .tint(Brand.orange)
        }
    }
}

/// Chọn giữa màn đăng nhập và app chính (tương đương AppRoot.kt bên Android).
struct RootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        Group {
            if app.isLoggedIn {
                MainTabView()
            } else {
                LoginView()
            }
        }
        .animation(.default, value: app.isLoggedIn)
        .overlay(alignment: .bottom) {
            if let message = app.message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Brand.ink.opacity(0.92), in: Capsule())
                    .padding(.bottom, 80)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: message) {
                        try? await Task.sleep(for: .seconds(2.5))
                        if app.message == message { app.message = nil }
                    }
            }
        }
        .animation(.default, value: app.message)
    }
}
