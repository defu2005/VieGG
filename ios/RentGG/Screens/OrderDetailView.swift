import RentGGCore
import SwiftUI

// Chi tiết đơn và mọi thao tác theo vai trò (tương đương frontend/src/pages/OrderDetailPage.tsx).
// Nút nào hiện ra do `OrderActions` (RentGGCore) quyết định; backend vẫn kiểm tra quyền thật.

struct OrderDetailView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.openURL) private var openURL
    let orderId: String

    @State private var state: LoadState<OrderDetail> = .loading
    @State private var handoverMode: String?
    @State private var busy = false
    @State private var pay: PayResponse?
    @State private var credentials: Credentials?
    @State private var otpNote = ""
    @State private var confirm: PendingConfirm?

    /// Thao tác cần hỏi lại trước khi làm.
    enum PendingConfirm: Identifiable {
        case cancel, reject
        var id: Self { self }
    }

    init(orderId: String) {
        self.orderId = orderId
    }

    var body: some View {
        LoadStateView(state: state, retry: { Task { await load() } }) { detail in
            let actions = OrderActions(order: detail.order, viewerId: app.me?.id, handoverMode: handoverMode)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    summary(detail)
                    if actions.canPay { paySection(detail.order) }
                    if actions.canApprove { approveSection }
                    if actions.canConfirmHandover { handoverSection(endsComplaints: actions.handoverEndsComplaints) }
                    if actions.canViewCredentials { credentialsSection }
                    if actions.canRequestOtp { requestOtpSection }
                    if actions.canSendOtp { sendOtpSection }
                    DisputesSection(orderId: detail.order.id, actions: actions)
                    if actions.canCancel {
                        PrimaryButton(title: "Hủy đơn", kind: .danger, loading: busy) { confirm = .cancel }
                            .padding(.top, 4)
                    }
                }
                .padding(12)
            }
            .refreshable { await load() }
        }
        .background(Brand.surface)
        .orangeNav("Chi tiết đơn")
        .task { await load() }
        .confirmationDialog(confirmTitle, isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) {
            switch confirm {
            case .cancel:
                Button("Hủy đơn", role: .destructive) { run("Đã hủy đơn.") { try await app.api.cancelOrder(id: orderId) } }
            case .reject:
                Button("Từ chối đơn", role: .destructive) { run("Đã từ chối đơn.") { try await app.api.rejectOrder(id: orderId) } }
            case nil:
                EmptyView()
            }
            Button("Không", role: .cancel) {}
        }
    }

    private var confirmTitle: String {
        switch confirm {
        case .cancel: "Hủy đơn này? Phí hủy được tính theo chính sách."
        case .reject: "Từ chối đơn này? Người thuê sẽ được hoàn tiền."
        case nil: ""
        }
    }

    // MARK: - Các khối

    private func summary(_ detail: OrderDetail) -> some View {
        let o = detail.order
        return Card {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Đơn \(o.id.prefix(8))")
                        .font(.headline)
                    Spacer()
                    StatusChip(label: orderStatusLabel(o.status))
                }
                InfoRow(label: "Bắt đầu", value: fmtTime(o.startAt))
                InfoRow(label: "Kết thúc dự kiến", value: fmtTime(o.scheduledEndAt))
                if let actual = o.actualEndAt {
                    InfoRow(label: "Kết thúc thực tế", value: fmtTime(actual))
                }
                Divider()
                InfoRow(label: "Tiền thuê", value: vnd(o.rentAmount ?? 0))
                InfoRow(label: "Tiền cọc", value: vnd(o.depositAmount ?? 0))
                InfoRow(label: "Tổng thanh toán", value: vnd(o.amountToPay ?? 0), emphasized: true)
                if o.status == "PENDING_PAYMENT", let expires = o.paymentExpiresAt {
                    InfoRow(label: "Hạn thanh toán", value: fmtTime(expires))
                }
                if o.status == "AWAITING_OWNER_APPROVAL", let expires = o.approvalExpiresAt {
                    InfoRow(label: "Hạn chủ account duyệt", value: fmtTime(expires))
                }
                if let window = o.complaintWindowEndsAt {
                    InfoRow(label: "Hạn khiếu nại", value: fmtTime(window))
                }
                if let deposit = detail.deposit {
                    HStack {
                        Text("Trạng thái cọc").font(.subheadline).foregroundStyle(Brand.muted)
                        Spacer()
                        StatusChip(label: depositStatusLabel(deposit.status))
                    }
                }
                if let reason = o.cancelReason, !reason.isEmpty {
                    InfoRow(label: "Lý do hủy", value: reason)
                }
                if let refunds = detail.refunds, !refunds.isEmpty {
                    Text("Đơn có \(refunds.count) khoản hoàn tiền.")
                        .font(.footnote)
                        .foregroundStyle(Brand.muted)
                }
            }
        }
    }

    private func paySection(_ order: Order) -> some View {
        SectionCard("Thanh toán") {
            if let pay {
                InfoRow(label: "Mã giao dịch", value: String(pay.intentId.prefix(8)))
                InfoRow(label: "Số tiền", value: vnd(pay.amount), emphasized: true)
                if let link = pay.checkoutUrl, let url = URL(string: link), url.scheme == "https" {
                    PrimaryButton(title: "Mở trang thanh toán") { openURL(url) }
                }
                if app.isDevBuild {
                    PrimaryButton(title: "Giả lập thanh toán thành công (dev)", kind: .secondary, loading: busy) {
                        run("Đã giả lập thanh toán thành công.") {
                            try await app.api.simulatePayment(intentId: pay.intentId)
                            self.pay = nil
                        }
                    }
                }
            } else {
                PrimaryButton(title: "Thanh toán \(vnd(order.amountToPay ?? 0))", loading: busy) {
                    run("Đã tạo giao dịch thanh toán.", reload: false) {
                        pay = try await app.api.pay(orderId: orderId)
                    }
                }
            }
        }
    }

    private var approveSection: some View {
        SectionCard("Duyệt đơn") {
            Text("Người thuê đã thanh toán. Duyệt để xác nhận đơn, hoặc từ chối để hoàn tiền.")
                .font(.subheadline)
                .foregroundStyle(Brand.muted)
            HStack(spacing: 10) {
                PrimaryButton(title: "Duyệt đơn", loading: busy) {
                    run("Đã duyệt đơn.") { try await app.api.approveOrder(id: orderId) }
                }
                PrimaryButton(title: "Từ chối", kind: .secondary, loading: busy) { confirm = .reject }
            }
        }
    }

    private func handoverSection(endsComplaints: Bool) -> some View {
        SectionCard("Bàn giao account") {
            Text("Sau khi đã kiểm tra và đăng nhập được, hãy xác nhận đã nhận account.")
                .font(.subheadline)
                .foregroundStyle(Brand.muted)
            if endsComplaints {
                Text("Nếu không đăng nhập được hoặc account không đúng mô tả, hãy tạo khiếu nại trước: sau khi xác nhận sẽ không khiếu nại được nữa.")
                    .font(.footnote)
                    .foregroundStyle(Brand.amber)
            }
            PrimaryButton(title: "Xác nhận đã nhận account", loading: busy) {
                run("Đã xác nhận nhận account.") { try await app.api.confirmHandover(orderId: orderId) }
            }
        }
    }

    private var credentialsSection: some View {
        SectionCard("Thông tin đăng nhập") {
            Text("Chỉ xem được trong khung giờ thuê. Mỗi lần xem đều được ghi nhật ký.")
                .font(.footnote)
                .foregroundStyle(Brand.muted)
            if let credentials {
                SecretRow(label: "Tài khoản", value: credentials.username)
                SecretRow(label: "Mật khẩu", value: credentials.password)
                if let otp = credentials.otpNote, !otp.isEmpty {
                    SecretRow(label: "Mã OTP", value: otp)
                }
                InfoRow(label: "Dùng được đến", value: fmtTime(credentials.availableUntil))
                PrimaryButton(title: "Ẩn thông tin", kind: .secondary) { self.credentials = nil }
            } else {
                PrimaryButton(title: "Xem thông tin đăng nhập", kind: .secondary, loading: busy) {
                    run(nil, reload: false) { credentials = try await app.api.credentials(orderId: orderId) }
                }
            }
        }
    }

    private var requestOtpSection: some View {
        SectionCard("Mã OTP") {
            Text("Game này cần OTP. Hãy yêu cầu chủ account gửi mã khi bạn đăng nhập.")
                .font(.subheadline)
                .foregroundStyle(Brand.muted)
            PrimaryButton(title: "Yêu cầu mã OTP", kind: .secondary, loading: busy) {
                run("Đã gửi yêu cầu OTP.") { try await app.api.requestOtp(orderId: orderId) }
            }
        }
    }

    private var sendOtpSection: some View {
        SectionCard("Gửi mã OTP cho người thuê") {
            TextField("Mã OTP", text: $otpNote)
                .textFieldStyle(.roundedBorder)
                .textContentType(.oneTimeCode)
                .keyboardType(.asciiCapable)
                .autocorrectionDisabled()
                .onChange(of: otpNote) { _, value in
                    if value.count > 64 { otpNote = String(value.prefix(64)) }
                }
            Text("Mã tự hết hạn sau 10 phút.")
                .font(.caption)
                .foregroundStyle(Brand.muted)
            PrimaryButton(title: "Gửi mã", enabled: !otpNote.trimmed.isEmpty, loading: busy) {
                let note = otpNote.trimmed
                run("Đã gửi mã OTP cho người thuê.") {
                    try await app.api.sendOtpNote(orderId: orderId, note: note)
                    otpNote = ""
                }
            }
        }
    }

    // MARK: - Tải và thao tác

    private func load() async {
        if state.value == nil { state = .loading }
        do {
            let detail = try await app.api.order(id: orderId)
            state = .ready(detail)
            if handoverMode == nil {
                handoverMode = try? await app.api.game(id: detail.order.gameId).handoverMode
            }
        } catch {
            state = .error(errorText(error))
        }
    }

    /// Chạy một thao tác (có trạng thái đang xử lý), rồi tải lại đơn nếu `reload`.
    private func run(_ success: String?, reload: Bool = true, _ operation: @escaping () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            if await app.perform(success: success, operation), reload {
                credentials = nil
                await load()
            }
        }
    }
}
