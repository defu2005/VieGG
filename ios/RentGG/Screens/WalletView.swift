import RentGGCore
import SwiftUI

// Ví: số dư, rút tiền, lịch sử rút, tiền cọc khi đi thuê (tương đương frontend/src/pages/WalletPage.tsx).

struct WalletView: View {
    @EnvironmentObject private var app: AppState
    @State private var state: LoadState<WalletData> = .loading
    @State private var showWithdraw = false

    struct WalletData {
        let balances: Balances
        let withdrawals: [Withdrawal]
        let deposits: [Deposit]
    }

    var body: some View {
        LoadStateView(state: state, retry: { Task { await load() } }) { data in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    balancesCard(data.balances)
                    withdrawalsCard(data.withdrawals)
                    depositsCard(data.deposits)
                }
                .padding(12)
            }
            .refreshable { await load() }
        }
        .background(Brand.surface)
        .orangeNav("Ví")
        .task { await load() }
        .sheet(isPresented: $showWithdraw) {
            WithdrawForm(available: state.value?.balances.available ?? 0) {
                await load()
            }
        }
    }

    private func balancesCard(_ b: Balances) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                Text("Có thể rút").font(.subheadline).foregroundStyle(Brand.muted)
                Text(vnd(b.available))
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(Brand.priceRed)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 10) {
                    Stat(label: "Đang giữ (duyệt)", value: b.reviewHold)
                    Stat(label: "Đang giữ (khiếu nại)", value: b.disputeHold)
                    Stat(label: "Đang chuyển khoản", value: b.payoutProcessing)
                }
                PrimaryButton(title: "Rút tiền", enabled: b.available > 0) { showWithdraw = true }
            }
        }
    }

    private func withdrawalsCard(_ items: [Withdrawal]) -> some View {
        SectionCard("Lịch sử rút tiền") {
            if items.isEmpty {
                Text("Chưa có yêu cầu rút tiền.").font(.subheadline).foregroundStyle(Brand.muted)
            }
            ForEach(items) { w in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(vnd(w.amount)).font(.subheadline.bold())
                        Spacer()
                        StatusChip(label: withdrawalStatusLabel(w.status))
                    }
                    let bank = [w.bank?["bankName"]?.stringValue, w.bank?["bankAccountNumber"]?.stringValue]
                        .compactMap { $0 }.joined(separator: " ")
                    Text("\(fmtTime(w.createdAt))\(bank.isEmpty ? "" : " · \(bank)")")
                        .font(.caption)
                        .foregroundStyle(Brand.muted)
                    if let note = w.failureReason ?? w.bankRef {
                        Text(note).font(.caption).foregroundStyle(w.failureReason != nil ? Brand.danger : Brand.muted)
                    }
                }
                .padding(.vertical, 4)
                if w.id != items.last?.id { Divider() }
            }
        }
    }

    private func depositsCard(_ items: [Deposit]) -> some View {
        SectionCard("Tiền cọc khi đi thuê") {
            if items.isEmpty {
                Text("Chưa có khoản cọc nào.").font(.subheadline).foregroundStyle(Brand.muted)
            }
            ForEach(items) { d in
                NavigationLink {
                    OrderDetailView(orderId: d.orderId)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Đơn \(d.orderId.prefix(8))").font(.subheadline.bold()).foregroundStyle(Brand.ink)
                            Spacer()
                            StatusChip(label: depositStatusLabel(d.status))
                        }
                        HStack {
                            Text(vnd(d.amount)).font(.subheadline).foregroundStyle(Brand.ink)
                            Spacer()
                            if let hold = d.holdUntil {
                                Text("Giữ đến \(fmtTime(hold))").font(.caption).foregroundStyle(Brand.muted)
                            }
                        }
                        if (d.deductedAmount ?? 0) > 0 || (d.refundedAmount ?? 0) > 0 {
                            Text("Khấu trừ \(vnd(d.deductedAmount ?? 0)) · Đã hoàn \(vnd(d.refundedAmount ?? 0))")
                                .font(.caption)
                                .foregroundStyle(Brand.muted)
                        }
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if d.id != items.last?.id { Divider() }
            }
        }
    }

    private func load() async {
        if state.value == nil { state = .loading }
        do {
            async let balances = app.api.balances()
            async let withdrawals = app.api.withdrawals()
            async let deposits = app.api.deposits()
            state = .ready(WalletData(balances: try await balances, withdrawals: try await withdrawals, deposits: try await deposits))
        } catch {
            state = .error(errorText(error))
        }
    }
}

private struct Stat: View {
    let label: String
    let value: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(Brand.muted)
            Text(vnd(value)).font(.subheadline.bold())
        }
    }
}

private struct WithdrawForm: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let available: Int
    let onDone: () async -> Void

    @State private var amount: Int?
    @State private var bankName = ""
    @State private var accountNumber = ""
    @State private var accountName = ""
    @State private var busy = false

    init(available: Int, onDone: @escaping () async -> Void) {
        self.available = available
        self.onDone = onDone
    }

    private var amountError: String? {
        guard let amount else { return nil }
        if amount <= 0 { return "Số tiền phải lớn hơn 0." }
        if amount > available { return "Vượt quá số dư có thể rút (\(vnd(available)))." }
        return nil
    }

    private var valid: Bool {
        (amount ?? 0) > 0 && amountError == nil
            && !bankName.trimmed.isEmpty && bankName.count <= 100
            && Validation.isValidBankAccount(accountNumber.trimmed)
            && !accountName.trimmed.isEmpty && accountName.count <= 100
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Số tiền (đ)", value: $amount, format: .number)
                        .keyboardType(.numberPad)
                    Button("Rút toàn bộ \(vnd(available))") { amount = available }
                        .font(.subheadline)
                        .foregroundStyle(Brand.orange)
                } footer: {
                    Text(amountError ?? "Chỉ rút được tối đa số dư có thể rút.")
                        .foregroundStyle(amountError == nil ? Brand.muted : Brand.danger)
                }
                Section {
                    TextField("Ngân hàng", text: $bankName)
                    TextField("Số tài khoản", text: $accountNumber)
                        .keyboardType(.numberPad)
                    TextField("Chủ tài khoản", text: $accountName)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                } footer: {
                    if !accountNumber.isEmpty && !Validation.isValidBankAccount(accountNumber.trimmed) {
                        Text("Số tài khoản gồm 6 đến 20 chữ số.").foregroundStyle(Brand.danger)
                    }
                }
            }
            .navigationTitle("Rút tiền")
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
        guard let amount else { return }
        busy = true
        defer { busy = false }
        let ok = await app.perform(success: "Đã gửi yêu cầu rút tiền.") {
            try await app.api.withdraw(amount: amount, bankName: bankName.trimmed,
                                       accountNumber: accountNumber.trimmed, accountName: accountName.trimmed)
        }
        if ok {
            await onDone()
            dismiss()
        }
    }
}
