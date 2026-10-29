import RentGGCore
import SwiftUI

// Account cho thuê (tương đương frontend/src/pages/MyListingsPage.tsx):
// account game của chủ (thêm, đổi mật khẩu, lấy lại) và listing (tạo, sửa, gửi duyệt, tạm dừng).

struct MyListingsView: View {
    @EnvironmentObject private var app: AppState
    @State private var state: LoadState<OwnerData> = .loading
    @State private var games: [GameView] = []
    @State private var sheet: Sheet?
    @State private var reclaimTarget: GameAccount?
    @State private var busyId: String?

    struct OwnerData {
        let accounts: [GameAccount]
        let listings: [Listing]
    }

    enum Sheet: Identifiable {
        case addAccount
        case createListing
        case editListing(Listing)
        case rotate(GameAccount)

        var id: String {
            switch self {
            case .addAccount: "add"
            case .createListing: "create"
            case .editListing(let l): "edit-\(l.id)"
            case .rotate(let a): "rotate-\(a.id)"
            }
        }
    }

    private func gameName(_ id: String) -> String {
        games.first { $0.id == id }?.name ?? String(id.prefix(8))
    }

    var body: some View {
        LoadStateView(state: state, retry: { Task { await load() } }) { data in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    accountsCard(data.accounts)
                    listingsCard(data)
                }
                .padding(12)
            }
            .refreshable { await load() }
        }
        .background(Brand.surface)
        .orangeNav("Account cho thuê")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Thêm account game", systemImage: "person.badge.plus") { sheet = .addAccount }
                    Button("Tạo listing", systemImage: "square.and.pencil") { sheet = .createListing }
                        .disabled(state.value?.accounts.isEmpty ?? true)
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Thêm")
            }
        }
        .task {
            if let all = try? await app.api.games() { games = all }
        }
        .task { await load() }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .addAccount:
                AddGameAccountForm(games: games.filter { $0.tosStatus == "ALLOWED" }) { await load() }
            case .createListing:
                ListingForm(mode: .create(accounts: state.value?.accounts ?? []), gameName: gameName) { await load() }
            case .editListing(let listing):
                ListingForm(mode: .edit(listing), gameName: gameName) { await load() }
            case .rotate(let account):
                RotateCredentialsForm(account: account) { await load() }
            }
        }
        .confirmationDialog(
            "Lấy lại account? Người thuê sẽ không còn dùng được account này.",
            isPresented: Binding(get: { reclaimTarget != nil }, set: { if !$0 { reclaimTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button("Lấy lại account", role: .destructive) {
                if let account = reclaimTarget {
                    act(account.id, "Đã lấy lại account.") { try await app.api.reclaim(accountId: account.id) }
                }
            }
            Button("Không", role: .cancel) {}
        }
    }

    // MARK: - Account game

    private func accountsCard(_ accounts: [GameAccount]) -> some View {
        SectionCard("Account game của bạn") {
            Text("Thông tin đăng nhập được mã hóa khi lưu. Account phải được xác minh thông tin khôi phục trước khi đăng listing.")
                .font(.caption)
                .foregroundStyle(Brand.muted)
            if accounts.isEmpty {
                PrimaryButton(title: "Thêm account game đầu tiên", kind: .secondary) { sheet = .addAccount }
            }
            ForEach(accounts) { account in
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.loginHint ?? String(account.id.prefix(8))).font(.subheadline.bold())
                            Text([gameName(account.gameId), account.serverRegion].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(Brand.muted)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 4) {
                            StatusChip(label: securityStateLabel(account.securityState))
                            TagChip(
                                text: account.recoveryVerifiedAt != nil ? "Đã xác minh khôi phục" : "Chưa xác minh khôi phục",
                                color: account.recoveryVerifiedAt != nil ? Brand.green : Brand.amber
                            )
                        }
                    }
                    if account.securityState == "ROTATION_PENDING", let due = account.rotationDueAt {
                        Text("Cần đổi mật khẩu trước \(fmtTime(due)), nếu không đơn kế tiếp sẽ bị hủy.")
                            .font(.caption)
                            .foregroundStyle(Brand.danger)
                    }
                    HStack(spacing: 10) {
                        PrimaryButton(title: "Đổi mật khẩu", kind: .secondary) { sheet = .rotate(account) }
                        PrimaryButton(title: "Lấy lại", kind: .secondary, loading: busyId == account.id) { reclaimTarget = account }
                    }
                }
                .padding(10)
                .background(Brand.surface, in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    // MARK: - Listing

    private func listingsCard(_ data: OwnerData) -> some View {
        SectionCard("Listing của bạn") {
            if data.listings.isEmpty {
                Text(data.accounts.isEmpty ? "Thêm account game trước, rồi tạo listing." : "Chưa có listing nào.")
                    .font(.subheadline)
                    .foregroundStyle(Brand.muted)
                if !data.accounts.isEmpty {
                    PrimaryButton(title: "Tạo listing", kind: .secondary) { sheet = .createListing }
                }
            }
            ForEach(data.listings) { listing in
                let actions = ListingActions(status: listing.status)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top) {
                        Text(listing.title).font(.subheadline.bold()).lineLimit(2)
                        Spacer()
                        StatusChip(label: listingStatusLabel(listing.status))
                    }
                    Text("\(vnd(listing.pricePerHour))/giờ · cọc \(vnd(listing.depositAmount))")
                        .font(.caption)
                        .foregroundStyle(Brand.muted)
                    if let note = listing.reviewNote, !note.isEmpty {
                        Text("\(listing.status == "SUSPENDED" ? "Lý do đình chỉ" : "Lý do từ chối"): \(note)")
                            .font(.caption)
                            .foregroundStyle(Brand.danger)
                    }
                    HStack(spacing: 10) {
                        if actions.canSubmit {
                            PrimaryButton(title: "Gửi duyệt", loading: busyId == listing.id) {
                                act(listing.id, "Đã gửi listing để duyệt.") { try await app.api.submitListing(id: listing.id) }
                            }
                        }
                        if actions.canPause {
                            PrimaryButton(title: "Tạm dừng", kind: .secondary, loading: busyId == listing.id) {
                                act(listing.id, "Đã tạm dừng listing.") { try await app.api.pauseListing(id: listing.id) }
                            }
                        }
                        if actions.canEdit {
                            PrimaryButton(title: "Sửa", kind: .secondary) { sheet = .editListing(listing) }
                        }
                    }
                }
                .padding(10)
                .background(Brand.surface, in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    // MARK: - Tải và thao tác

    private func load() async {
        if state.value == nil { state = .loading }
        do {
            async let accounts = app.api.gameAccounts()
            async let listings = app.api.myListings()
            state = .ready(OwnerData(accounts: try await accounts, listings: try await listings))
        } catch {
            state = .error(errorText(error))
        }
    }

    private func act(_ id: String, _ success: String, _ operation: @escaping () async throws -> Void) {
        Task {
            busyId = id
            defer { busyId = nil }
            if await app.perform(success: success, operation) {
                await load()
            }
        }
    }
}
