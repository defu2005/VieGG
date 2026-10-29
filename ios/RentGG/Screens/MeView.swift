import RentGGCore
import SwiftUI

// Tab "Của tôi": hồ sơ, xác minh danh tính (tùy chọn), ví, account cho thuê, đăng xuất
// (tương đương MeTab bên Android và frontend/src/pages/ProfilePage.tsx).

struct MeView: View {
    @EnvironmentObject private var app: AppState
    @State private var showKyc = false
    @State private var confirmLogout = false
    @State private var loadFailed = false

    var body: some View {
        NavigationStack {
            Group {
                if let user = app.me {
                    ScrollView {
                        VStack(spacing: 12) {
                            profileCard(user)
                            menuCard
                            Card {
                                MenuRow(icon: "rectangle.portrait.and.arrow.right", text: "Đăng xuất", showsChevron: false) {
                                    confirmLogout = true
                                }
                            }
                        }
                        .padding(12)
                    }
                    .refreshable { await app.refreshMe() }
                } else if loadFailed {
                    VStack(spacing: 12) {
                        Text("Không tải được hồ sơ.")
                            .foregroundStyle(Brand.danger)
                        Button("Thử lại") { loadFailed = false }
                            .buttonStyle(.borderedProminent)
                            .tint(Brand.orange)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ProgressView()
                        .tint(Brand.orange)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .task {
                            let ok = await app.refreshMe()
                            loadFailed = !ok
                        }
                }
            }
            .background(Brand.surface)
            .orangeNav("Của tôi")
            .sheet(isPresented: $showKyc) { KycForm() }
            .confirmationDialog("Đăng xuất khỏi VieGG?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Đăng xuất", role: .destructive) { app.logout() }
                Button("Không", role: .cancel) {}
            }
        }
    }

    private func profileCard(_ user: Me) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    Text(String(user.displayName.trimmed.prefix(1)).uppercased())
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .frame(width: 52, height: 52)
                        .background(Brand.orange, in: Circle())

                    VStack(alignment: .leading, spacing: 4) {
                        Text(user.displayName).font(.headline)
                        Text(user.email).font(.footnote).foregroundStyle(Brand.muted)
                        HStack(spacing: 4) {
                            let verified = user.kycStatus == "VERIFIED"
                            TagChip(text: kycText(user.kycStatus), color: verified ? Brand.green : Brand.muted)
                            if let status = user.status, status != "ACTIVE" {
                                TagChip(text: status, color: Brand.danger)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                }
                if let dob = user.dateOfBirth {
                    InfoRow(label: "Ngày sinh", value: dob)
                }
                if let roles = user.roles, !roles.isEmpty {
                    InfoRow(label: "Vai trò", value: roles.sorted().joined(separator: ", "))
                }
                if user.kycStatus == nil || user.kycStatus == "NONE" || user.kycStatus == "REJECTED" {
                    Button("Xác minh danh tính (tùy chọn)") { showKyc = true }
                        .font(.subheadline)
                        .foregroundStyle(Brand.orange)
                }
            }
        }
    }

    private var menuCard: some View {
        Card {
            VStack(spacing: 0) {
                NavigationLink {
                    WalletView()
                } label: {
                    MenuLabel(icon: "wallet.pass.fill", text: "Ví & rút tiền")
                }
                Divider().padding(.leading, 36)
                NavigationLink {
                    MyListingsView()
                } label: {
                    MenuLabel(icon: "gamecontroller.fill", text: "Account cho thuê của tôi")
                }
            }
            .buttonStyle(.plain)
        }
    }
}

private struct MenuLabel: View {
    let icon: String
    let text: String
    var showsChevron = true

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(Brand.orange)
                .frame(width: 24)
            Text(text).foregroundStyle(Brand.ink)
            Spacer()
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(Brand.muted)
            }
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct MenuRow: View {
    let icon: String
    let text: String
    var showsChevron = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MenuLabel(icon: icon, text: text, showsChevron: showsChevron)
        }
        .buttonStyle(.plain)
    }
}

/// Xác minh danh tính (UC KYC, không bắt buộc). Backend chỉ lưu 4 số cuối giấy tờ.
private struct KycForm: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var fullName = ""
    @State private var idNumber = ""
    @State private var phone = ""
    @State private var busy = false

    private var valid: Bool {
        !fullName.trimmed.isEmpty && fullName.count <= 120
            && (6...20).contains(idNumber.trimmed.count) && phone.count <= 20
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Họ và tên", text: $fullName)
                        .textContentType(.name)
                    TextField("Số CCCD/hộ chiếu", text: $idNumber)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Số điện thoại (không bắt buộc)", text: $phone)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                } footer: {
                    Text("Hiện không bắt buộc để thuê hoặc cho thuê. Hệ thống chỉ lưu 4 số cuối của giấy tờ và không lưu ảnh.")
                }
            }
            .navigationTitle("Xác minh danh tính")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button("Gửi") { Task { await submit() } }.disabled(!valid)
                    }
                }
            }
        }
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let phoneValue = phone.trimmed
        let ok = await app.perform(success: "Đã gửi thông tin xác minh, chờ quản trị viên duyệt.") {
            try await app.api.submitKyc(fullName: fullName.trimmed, idNumber: idNumber.trimmed, phone: phoneValue.isEmpty ? nil : phoneValue)
        }
        if ok {
            await app.refreshMe()
            dismiss()
        }
    }
}
