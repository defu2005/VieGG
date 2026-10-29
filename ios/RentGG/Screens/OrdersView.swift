import RentGGCore
import SwiftUI

// Tương đương OrdersTab trong TabScreens.kt: đơn tôi thuê và đơn tôi cho thuê.

struct OrdersView: View {
    @EnvironmentObject private var app: AppState
    @State private var role: OrderRole = .renter
    @State private var orders: LoadState<[Order]> = .loading
    @State private var gameNames: [String: String] = [:]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Vai trò", selection: $role) {
                    Text("Tôi thuê").tag(OrderRole.renter)
                    Text("Tôi cho thuê").tag(OrderRole.owner)
                }
                .pickerStyle(.segmented)
                .padding(12)

                LoadStateView(state: orders, retry: { Task { await load() } }) { list in
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            if list.isEmpty {
                                EmptyStateView(text: "Chưa có đơn nào")
                            }
                            ForEach(list) { order in
                                NavigationLink {
                                    OrderDetailView(orderId: order.id)
                                } label: {
                                    OrderCard(order: order, gameName: gameNames[order.gameId])
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
            .orangeNav("Đơn thuê")
            .task(id: role) {
                orders = .loading
                await load()
            }
            .task { await loadGames() }
        }
    }

    private func load() async {
        do {
            orders = .ready(try await app.api.orders(role: role))
        } catch {
            orders = .error(errorText(error))
        }
    }

    private func loadGames() async {
        if let all = try? await app.api.games() {
            gameNames = Dictionary(all.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        }
    }
}

private struct OrderCard: View {
    let order: Order
    let gameName: String?

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(gameName ?? "Đơn \(order.id.prefix(8))")
                        .font(.body.bold())
                    Spacer()
                    StatusChip(label: orderStatusLabel(order.status))
                }
                Text("\(fmtTime(order.startAt)) → \(fmtTime(order.scheduledEndAt))")
                    .font(.footnote)
                    .foregroundStyle(Brand.muted)
                HStack {
                    Text("Thanh toán")
                        .font(.footnote)
                        .foregroundStyle(Brand.muted)
                    Spacer()
                    Text(vnd(order.amountToPay ?? 0))
                        .font(.subheadline.bold())
                        .foregroundStyle(Brand.priceRed)
                }
            }
        }
    }
}
