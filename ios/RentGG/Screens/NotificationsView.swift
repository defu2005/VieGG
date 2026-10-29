import RentGGCore
import SwiftUI

// Tương đương NotificationsTab trong TabScreens.kt: danh sách, lọc chưa đọc, chạm để đánh dấu đã đọc.

struct NotificationsView: View {
    @EnvironmentObject private var app: AppState
    /// Gọi sau khi đánh dấu đã đọc để tab bar cập nhật số chưa đọc.
    let onChanged: () -> Void
    @State private var unreadOnly = false
    @State private var items: LoadState<[NotificationItem]> = .loading

    init(onChanged: @escaping () -> Void) {
        self.onChanged = onChanged
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Lọc", selection: $unreadOnly) {
                    Text("Tất cả").tag(false)
                    Text("Chưa đọc").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(12)

                LoadStateView(state: items, retry: { Task { await load() } }) { list in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            if list.isEmpty {
                                EmptyStateView(text: unreadOnly ? "Không có thông báo chưa đọc" : "Chưa có thông báo")
                            }
                            ForEach(list) { note in
                                Button {
                                    Task { await markRead(note) }
                                } label: {
                                    NotificationRow(note: note)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, 12)
                    }
                    .refreshable { await load() }
                }
            }
            .background(Brand.surface)
            .orangeNav("Thông báo")
            .task(id: unreadOnly) { await load() }
        }
    }

    private func load() async {
        if items.value == nil { items = .loading }
        do {
            items = .ready(try await app.api.notifications(unreadOnly: unreadOnly).content)
        } catch {
            items = .error(errorText(error))
        }
    }

    private func markRead(_ note: NotificationItem) async {
        guard note.readAt == nil else { return }
        if await app.perform({ try await app.api.markRead(notificationId: note.id) }) {
            await load()
            onChanged()
        }
    }
}

private struct NotificationRow: View {
    let note: NotificationItem

    var body: some View {
        let unread = note.readAt == nil
        Card {
            HStack(alignment: .top, spacing: 10) {
                Circle()
                    .fill(unread ? Brand.priceRed : Color.clear)
                    .frame(width: 8, height: 8)
                    .padding(.top, 6)
                VStack(alignment: .leading, spacing: 4) {
                    Text(note.title)
                        .fontWeight(unread ? .bold : .regular)
                    if let body = note.body, !body.isEmpty {
                        Text(body)
                            .font(.footnote)
                            .foregroundStyle(Brand.muted)
                    }
                    Text(fmtTime(note.createdAt))
                        .font(.caption2)
                        .foregroundStyle(Brand.muted)
                }
            }
        }
    }
}
