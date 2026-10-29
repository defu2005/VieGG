import RentGGCore
import SwiftUI

// Tương đương MainScreen.kt: thanh tab 4 mục, có huy hiệu số thông báo chưa đọc.

struct MainTabView: View {
    @EnvironmentObject private var app: AppState
    @State private var selection: Tab = .home
    @State private var unread = 0

    enum Tab: Hashable {
        case home, orders, inbox, me
    }

    var body: some View {
        TabView(selection: $selection) {
            HomeView()
                .tabItem { Label("Trang chủ", systemImage: "house.fill") }
                .tag(Tab.home)

            OrdersView()
                .tabItem { Label("Đơn thuê", systemImage: "list.bullet.rectangle.fill") }
                .tag(Tab.orders)

            NotificationsView {
                Task { await refreshUnread() }
            }
            .tabItem { Label("Thông báo", systemImage: "bell.fill") }
            .badge(unread)
            .tag(Tab.inbox)

            MeView()
                .tabItem { Label("Của tôi", systemImage: "person.crop.circle.fill") }
                .tag(Tab.me)
        }
        .tint(Brand.orange)
        .task { await app.refreshMe() }
        .task(id: selection) { await refreshUnread() }
    }

    private func refreshUnread() async {
        if let count = try? await app.api.unreadCount() {
            unread = count
        }
    }
}
