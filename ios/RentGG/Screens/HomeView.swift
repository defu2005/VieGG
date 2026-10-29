import RentGGCore
import SwiftUI

// Tương đương HomeTab trong MainScreen.kt: tìm kiếm, lọc theo game, sắp xếp giá.

enum ListingSort: CaseIterable, Identifiable {
    case standard, priceAsc, priceDesc

    var id: Self { self }

    var label: String {
        switch self {
        case .standard: "Mặc định"
        case .priceAsc: "Giá thấp → cao"
        case .priceDesc: "Giá cao → thấp"
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var app: AppState
    @State private var query = ""
    @State private var gameId: String?
    @State private var sort: ListingSort = .standard
    @State private var autoOnly = false
    @State private var games: [GameView] = []
    @State private var listings: LoadState<[Listing]> = .loading

    private var gameNames: [String: String] {
        Dictionary(games.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
    }

    /// Danh sách sau khi áp dụng từ khóa, lọc "Tự nhận đơn" và sắp xếp.
    private var shown: [Listing] {
        var items = listings.value ?? []
        let keyword = query.trimmed
        if !keyword.isEmpty {
            items = items.filter {
                $0.title.localizedCaseInsensitiveContains(keyword)
                    || ($0.description ?? "").localizedCaseInsensitiveContains(keyword)
            }
        }
        if autoOnly {
            items = items.filter { $0.autoAccept == true }
        }
        switch sort {
        case .standard: break
        case .priceAsc: items.sort { $0.pricePerHour < $1.pricePerHour }
        case .priceDesc: items.sort { $0.pricePerHour > $1.pricePerHour }
        }
        return items
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar

                LoadStateView(state: listings, retry: { Task { await loadListings() } }) { _ in
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            gamePicker
                            sortChips

                            if shown.isEmpty {
                                EmptyStateView(text: "Chưa có account phù hợp")
                            }
                            ForEach(shown) { listing in
                                NavigationLink {
                                    ListingDetailView(listingId: listing.id)
                                } label: {
                                    ListingCard(listing: listing, gameName: gameNames[listing.gameId] ?? "Game")
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(12)
                    }
                    .refreshable { await loadListings() }
                }
            }
            .background(Brand.surface)
            .toolbar(.hidden, for: .navigationBar)
            .task { await loadGames() }
            .task(id: gameId) { await loadListings() }
        }
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Brand.muted)
            TextField("Tìm tên account, rank, skin…", text: $query)
                .submitLabel(.search)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Brand.muted)
                }
                .accessibilityLabel("Xóa từ khóa")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.white, in: Capsule())
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(Brand.orange)
    }

    private var gamePicker: some View {
        Card {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 16) {
                    GameCell(name: nil, selected: gameId == nil) { gameId = nil }
                    ForEach(games) { game in
                        GameCell(name: game.name, selected: gameId == game.id) { gameId = game.id }
                    }
                }
            }
        }
    }

    private var sortChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ListingSort.allCases) { option in
                    FilterChip(title: option.label, selected: sort == option) { sort = option }
                }
                FilterChip(title: "Tự nhận đơn", selected: autoOnly) { autoOnly.toggle() }
            }
        }
    }

    private func loadGames() async {
        if let all = try? await app.api.games() {
            games = all.filter { $0.tosStatus == "ALLOWED" }
        }
    }

    private func loadListings() async {
        if listings.value == nil { listings = .loading }
        do {
            listings = .ready(try await app.api.searchListings(gameId: gameId))
        } catch {
            listings = .error(errorText(error))
        }
    }
}

private struct GameCell: View {
    /// nil = ô "Tất cả".
    let name: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                if let name {
                    GameTile(name: name)
                } else {
                    Text("All")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(selected ? .white : Brand.muted)
                        .frame(width: 44, height: 44)
                        .background(selected ? Brand.orange : Color(hex: 0xEDEDF0), in: RoundedRectangle(cornerRadius: 12))
                }
                Text(name ?? "Tất cả")
                    .font(.caption2)
                    .lineLimit(1)
                    .fontWeight(selected ? .bold : .regular)
                    .foregroundStyle(selected ? Brand.orange : Color(hex: 0x444444))
            }
            .frame(width: 64)
        }
        .buttonStyle(.plain)
    }
}

struct FilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(selected ? Brand.orange : Brand.ink)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Brand.orangeSoft : Color.white, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct ListingCard: View {
    let listing: Listing
    let gameName: String

    var body: some View {
        Card {
            HStack(alignment: .top, spacing: 12) {
                GameTile(name: gameName, size: 56)

                VStack(alignment: .leading, spacing: 6) {
                    Text(listing.title)
                        .font(.body.bold())
                        .lineLimit(2)

                    HStack(spacing: 4) {
                        TagChip(text: gameName, color: Brand.blue)
                        if listing.autoAccept == true {
                            TagChip(text: "Tự nhận đơn", color: Brand.green)
                        }
                        TagChip(text: "Cọc \(vnd(listing.depositAmount))")
                    }

                    // Thuộc tính tự do do chủ account khai (rank, skin…): tối đa 3 mục.
                    if let attributes = listing.attributes, !attributes.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(attributes.keys.sorted().prefix(3), id: \.self) { key in
                                TagChip(text: "\(key): \(attributes[key]?.display ?? "")", color: Brand.muted)
                            }
                        }
                    }

                    HStack(alignment: .bottom) {
                        PriceText(perHour: listing.pricePerHour)
                        Spacer()
                        Text(endModeText(listing.endMode))
                            .font(.caption2)
                            .foregroundStyle(Brand.muted)
                            .lineLimit(1)
                    }
                }
            }
        }
    }
}
