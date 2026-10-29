import RentGGCore
import SwiftUI

// Tương đương AuthScreens.kt: đăng nhập và đăng ký.

struct LoginView: View {
    @EnvironmentObject private var app: AppState
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    header
                    VStack(spacing: 12) {
                        TextField("Email", text: $email)
                            .textContentType(.username)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)

                        SecureField("Mật khẩu", text: $password)
                            .textContentType(.password)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit { Task { await login() } }

                        if let error {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(Brand.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        PrimaryButton(
                            title: "Đăng nhập",
                            enabled: !email.trimmed.isEmpty && !password.isEmpty,
                            loading: busy
                        ) {
                            Task { await login() }
                        }
                        .padding(.top, 4)

                        NavigationLink {
                            RegisterView()
                        } label: {
                            Text("Chưa có tài khoản? Đăng ký")
                                .foregroundStyle(Brand.orange)
                        }
                        .padding(.top, 4)
                    }
                    .padding(24)
                }
            }
            .ignoresSafeArea(edges: .top)
            .background(Color.white)
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text("VieGG")
                .font(.system(size: 36, weight: .heavy))
                .foregroundStyle(.white)
            Text("Thuê account game · Nhận ngay · Có bảo hộ")
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.9))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 260)
        .background(LinearGradient(colors: [Brand.orange, Brand.priceRed], startPoint: .top, endPoint: .bottom))
    }

    private func login() async {
        guard !busy, !email.trimmed.isEmpty, !password.isEmpty else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await app.api.login(email: email.trimmed, password: password)
            await app.didLogin()
        } catch {
            self.error = errorText(error)
        }
    }
}

struct RegisterView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var name = ""
    @State private var password = ""
    @State private var dob = ""
    @State private var busy = false
    @State private var error: String?

    private var dobHint: String? {
        let value = dob.trimmed
        if value.isEmpty { return nil }
        if !Validation.isValidDate(value) { return "Ngày sinh phải có dạng YYYY-MM-DD." }
        if !Validation.isAtLeast(age: 18, dateOfBirth: value) { return "Bạn cần đủ 18 tuổi để đăng ký." }
        return nil
    }

    private var valid: Bool {
        Validation.isValidEmail(email.trimmed) && !name.trimmed.isEmpty && password.count >= 10
            && Validation.isAtLeast(age: 18, dateOfBirth: dob.trimmed)
    }

    var body: some View {
        Form {
            Section {
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Tên hiển thị", text: $name)
                SecureField("Mật khẩu (tối thiểu 10 ký tự)", text: $password)
                    .textContentType(.newPassword)
                TextField("Ngày sinh (YYYY-MM-DD)", text: $dob)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
            } footer: {
                Text(dobHint ?? "Phải đủ 18 tuổi.")
                    .foregroundStyle(dobHint == nil ? Brand.muted : Brand.danger)
            }

            if let error {
                Section {
                    Text(error).foregroundStyle(Brand.danger)
                }
            }

            Section {
                PrimaryButton(title: "Đăng ký", enabled: valid, loading: busy) {
                    Task { await register() }
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        .orangeNav("Đăng ký")
    }

    private func register() async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await app.api.register(
                email: email.trimmed,
                password: password,
                displayName: name.trimmed,
                dateOfBirth: dob.trimmed
            )
            app.show("Đăng ký thành công, hãy đăng nhập.")
            dismiss()
        } catch {
            self.error = errorText(error)
        }
    }
}
