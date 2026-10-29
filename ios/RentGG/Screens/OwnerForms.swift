import RentGGCore
import SwiftUI

// Các form của chủ account: thêm account game, đổi mật khẩu (rotation), tạo/sửa listing.

struct AddGameAccountForm: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let games: [GameView]
    let onDone: () async -> Void

    @State private var gameId = ""
    @State private var serverRegion = ""
    @State private var username = ""
    @State private var password = ""
    @State private var recoveryEmailOwned = false
    @State private var recoveryPhoneOwned = false
    @State private var twoFactorOwned = false
    @State private var accountKey = ""
    @State private var busy = false

    init(games: [GameView], onDone: @escaping () async -> Void) {
        self.games = games
        self.onDone = onDone
    }

    private var selectedGame: GameView? { games.first { $0.id == gameId } }
    private var needsKey: Bool { selectedGame?.needsAccountKey ?? false }
    private var needsRegion: Bool { needsKey && (selectedGame?.accountKey?.perServer ?? false) }
    private var keyLabel: String { selectedGame?.accountKeyLabel ?? "ID trong game" }

    private var valid: Bool {
        !gameId.isEmpty && !username.trimmed.isEmpty && !password.isEmpty
            && username.count <= 200 && password.count <= 200 && serverRegion.count <= 60
            && recoveryEmailOwned && recoveryPhoneOwned && twoFactorOwned
            && (!needsKey || (!accountKey.trimmed.isEmpty && accountKey.count <= 200))
            && (!needsRegion || !serverRegion.trimmed.isEmpty)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Game", selection: $gameId) {
                        Text("Chọn game").tag("")
                        ForEach(games) { game in
                            Text(game.name).tag(game.id)
                        }
                    }
                    TextField(needsRegion ? "Server (bắt buộc)" : "Server (không bắt buộc)", text: $serverRegion)
                }
                if needsKey {
                    Section {
                        TextField(selectedGame?.accountKey?.example ?? keyLabel, text: $accountKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: {
                        Text(keyLabel)
                    } footer: {
                        Text("Mỗi \(keyLabel) chỉ được đăng một lần trên VieGG.")
                    }
                }
                Section {
                    TextField("Tên đăng nhập", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Mật khẩu", text: $password)
                } header: {
                    Text("Thông tin đăng nhập game")
                } footer: {
                    Text("Được mã hóa khi lưu, chỉ người thuê đang trong giờ thuê mới xem được.")
                }
                Section {
                    Toggle("Tôi sở hữu email khôi phục", isOn: $recoveryEmailOwned)
                    Toggle("Tôi sở hữu số điện thoại khôi phục", isOn: $recoveryPhoneOwned)
                    Toggle("Tôi sở hữu xác thực 2 lớp", isOn: $twoFactorOwned)
                } footer: {
                    Text("Bạn phải xác nhận cả ba mục để thêm account.")
                }
                .tint(Brand.orange)
            }
            .navigationTitle("Thêm account game")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button("Lưu") { Task { await submit() } }.disabled(!valid)
                    }
                }
            }
        }
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let region = serverRegion.trimmed
        let ok = await app.perform(success: "Đã thêm account game.") {
            try await app.api.createGameAccount(
                gameId: gameId,
                serverRegion: region.isEmpty ? nil : region,
                username: username.trimmed,
                password: password,
                recoveryEmailOwned: recoveryEmailOwned,
                recoveryPhoneOwned: recoveryPhoneOwned,
                twoFactorOwned: twoFactorOwned,
                accountKey: needsKey ? accountKey.trimmed : nil
            )
        }
        if ok {
            await onDone()
            dismiss()
        }
    }
}

struct RotateCredentialsForm: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let account: GameAccount
    let onDone: () async -> Void

    @State private var newUsername = ""
    @State private var newPassword = ""
    @State private var loggedOutAllDevices = false
    @State private var busy = false

    init(account: GameAccount, onDone: @escaping () async -> Void) {
        self.account = account
        self.onDone = onDone
    }

    private var valid: Bool {
        !newPassword.isEmpty && newPassword.count <= 200 && newUsername.count <= 200 && loggedOutAllDevices
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Tên đăng nhập mới (nếu đổi)", text: $newUsername)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Mật khẩu mới", text: $newPassword)
                } header: {
                    Text(account.loginHint ?? "Account \(account.id.prefix(8))")
                }
                Section {
                    Toggle("Tôi đã đăng xuất tất cả thiết bị khác", isOn: $loggedOutAllDevices)
                        .tint(Brand.orange)
                } footer: {
                    Text("Bắt buộc, để người thuê trước không còn giữ phiên đăng nhập.")
                }
            }
            .navigationTitle("Đổi mật khẩu")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button("Lưu") { Task { await submit() } }.disabled(!valid)
                    }
                }
            }
        }
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let username = newUsername.trimmed
        let ok = await app.perform(success: "Đã cập nhật mật khẩu mới.") {
            try await app.api.rotateCredentials(
                accountId: account.id,
                newUsername: username.isEmpty ? nil : username,
                newPassword: newPassword,
                loggedOutAllDevices: loggedOutAllDevices
            )
        }
        if ok {
            await onDone()
            dismiss()
        }
    }
}

struct ListingForm: View {
    enum Mode {
        case create(accounts: [GameAccount])
        case edit(Listing)
    }

    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let mode: Mode
    let gameName: (String) -> String
    let onDone: () async -> Void

    @State private var accountId = ""
    @State private var draft: ListingDraft
    @State private var busy = false

    init(mode: Mode, gameName: @escaping (String) -> String, onDone: @escaping () async -> Void) {
        self.mode = mode
        self.gameName = gameName
        self.onDone = onDone
        if case .edit(let listing) = mode {
            _draft = State(initialValue: ListingDraft(listing: listing))
        } else {
            _draft = State(initialValue: ListingDraft())
        }
    }

    private var editing: Listing? {
        if case .edit(let listing) = mode { return listing }
        return nil
    }

    private var valid: Bool {
        draft.isValid && (editing != nil || !accountId.isEmpty)
    }

    var body: some View {
        NavigationStack {
            Form {
                if case .create(let accounts) = mode {
                    Section("Account") {
                        Picker("Account", selection: $accountId) {
                            Text("Chọn account").tag("")
                            ForEach(accounts) { account in
                                Text("\(account.loginHint ?? String(account.id.prefix(8))) · \(gameName(account.gameId)) · \(securityStateLabel(account.securityState).text)")
                                    .tag(account.id)
                            }
                        }
                    }
                }
                Section("Nội dung") {
                    TextField("Tiêu đề", text: $draft.title)
                    TextField("Mô tả (rank, skin, tướng…)", text: $draft.description, axis: .vertical)
                        .lineLimit(3...8)
                }
                Section {
                    LabeledContent("Giá / giờ (đ)") {
                        TextField("30000", value: $draft.pricePerHour, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Tiền cọc (đ)") {
                        TextField("200000", value: $draft.depositAmount, format: .number)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                    }
                } header: {
                    Text("Giá")
                } footer: {
                    Text("Tiền cọc phải nằm trong khoảng cho phép của game.")
                }
                Section("Cách cho thuê") {
                    Picker("Kết thúc thuê", selection: $draft.endMode) {
                        ForEach(EndMode.all, id: \.self) { mode in
                            Text(endModeText(mode)).tag(mode)
                        }
                    }
                    Toggle("Tự động nhận đơn (không cần duyệt từng đơn)", isOn: $draft.autoAccept)
                        .tint(Brand.orange)
                }
                if let listing = editing {
                    Section {
                        if listing.status == "ACTIVE" {
                            Text("Đổi giá, cọc, tự nhận đơn được áp dụng ngay. Chỉ sửa tiêu đề, mô tả, thông tin account hoặc thêm ảnh mới phải duyệt lại.")
                        } else if listing.status == "REJECTED" {
                            Text("Sau khi sửa, listing về nháp. Hãy gửi duyệt lại.")
                        }
                    }
                    .font(.footnote)
                    .foregroundStyle(Brand.muted)
                }
            }
            .navigationTitle(editing == nil ? "Tạo listing" : "Sửa listing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button(editing == nil ? "Tạo nháp" : "Lưu") { Task { await submit() } }.disabled(!valid)
                    }
                }
            }
        }
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let current = draft
        let ok: Bool
        if let listing = editing {
            ok = await app.perform(success: "Đã lưu listing.") {
                try await app.api.updateListing(listing, draft: current)
            }
        } else {
            let account = accountId
            ok = await app.perform(success: "Đã tạo listing nháp. Hãy gửi duyệt để hiển thị.") {
                try await app.api.createListing(gameAccountId: account, draft: current)
            }
        }
        if ok {
            await onDone()
            dismiss()
        }
    }
}
