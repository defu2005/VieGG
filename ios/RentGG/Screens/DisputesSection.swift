import RentGGCore
import SwiftUI

// Khiếu nại của một đơn (tương đương frontend/src/components/DisputesSection.tsx):
// xem danh sách và kết quả, thêm bằng chứng khi còn mở, mở khiếu nại mới theo vai trò.

struct DisputesSection: View {
    @EnvironmentObject private var app: AppState
    let orderId: String
    let actions: OrderActions

    @State private var disputes: [Dispute] = []
    @State private var loadError: String?
    @State private var showOpenForm = false

    init(orderId: String, actions: OrderActions) {
        self.orderId = orderId
        self.actions = actions
    }

    /// Loại được mở, trừ loại đang có khiếu nại mở (backend chỉ cho một khiếu nại mở mỗi loại).
    private var openableTypes: [String] {
        actions.disputeTypes.filter { type in !disputes.contains { $0.type == type && $0.status == "OPEN" } }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Luôn có một view thật để `.task` chạy cả khi chưa có gì để hiển thị.
            Color.clear
                .frame(height: 0)
                .task(id: "\(orderId)-\(actions.disputeTypes.count)") { await load() }
            if !disputes.isEmpty || !actions.disputeTypes.isEmpty || loadError != nil {
                SectionCard("Khiếu nại") {
                    if let loadError {
                        Text(loadError).font(.footnote).foregroundStyle(Brand.danger)
                    }
                    ForEach(disputes) { dispute in
                        DisputeRow(dispute: dispute, canAddEvidence: actions.isRenter || actions.isOwner) {
                            await load()
                        }
                    }
                    if !openableTypes.isEmpty {
                        PrimaryButton(title: "Tạo khiếu nại", kind: .secondary) { showOpenForm = true }
                    }
                }
            }
        }
        .sheet(isPresented: $showOpenForm) {
            OpenDisputeForm(orderId: orderId, types: openableTypes) {
                await load()
            }
        }
    }

    private func load() async {
        do {
            disputes = try await app.api.disputes(orderId: orderId)
            loadError = nil
        } catch {
            loadError = errorText(error)
        }
    }
}

private struct DisputeRow: View {
    @EnvironmentObject private var app: AppState
    let dispute: Dispute
    let canAddEvidence: Bool
    let onChanged: () async -> Void

    @State private var adding = false
    @State private var urls = ""
    @State private var note = ""
    @State private var busy = false

    init(dispute: Dispute, canAddEvidence: Bool, onChanged: @escaping () async -> Void) {
        self.dispute = dispute
        self.canAddEvidence = canAddEvidence
        self.onChanged = onChanged
    }

    private var parsed: [String] { Validation.parseUrls(urls) }
    private var invalid: [String] { Validation.invalidEvidenceUrls(parsed) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(disputeTypeText(dispute.type)).font(.subheadline.bold())
                Spacer()
                StatusChip(label: disputeStatusLabel(dispute.status))
            }
            if let description = dispute.description, !description.isEmpty {
                Text(description).font(.subheadline)
            }
            EvidenceList(evidence: dispute.evidence ?? [])
            if dispute.status == "OPEN", let deadline = dispute.deadlineAt {
                Text("Hạn phân xử: \(fmtTime(deadline))")
                    .font(.caption)
                    .foregroundStyle(Brand.muted)
            }
            if dispute.status != "OPEN" {
                InfoRow(label: "Khấu trừ cọc", value: vnd(dispute.depositDeduction ?? 0))
                InfoRow(label: "Hoàn tiền thuê", value: vnd(dispute.rentRefund ?? 0))
                let reason = [dispute.reasonCode, dispute.decisionNote].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " — ")
                if !reason.isEmpty {
                    InfoRow(label: "Lý do", value: reason)
                }
            }
            if canAddEvidence && dispute.status == "OPEN" {
                if adding {
                    TextField("Mỗi dòng một link https", text: $urls, axis: .vertical)
                        .lineLimit(2...6)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Ghi chú (không bắt buộc)", text: $note, axis: .vertical)
                        .lineLimit(1...4)
                        .textFieldStyle(.roundedBorder)
                    if !invalid.isEmpty {
                        Text("Chỉ nhận link https: \(invalid.joined(separator: ", "))")
                            .font(.caption)
                            .foregroundStyle(Brand.danger)
                    }
                    HStack(spacing: 10) {
                        PrimaryButton(title: "Gửi bằng chứng", enabled: !parsed.isEmpty && invalid.isEmpty && parsed.count <= 20, loading: busy) {
                            Task { await submit() }
                        }
                        PrimaryButton(title: "Đóng", kind: .secondary) { adding = false }
                    }
                } else {
                    Button("Thêm bằng chứng") { adding = true }
                        .font(.subheadline)
                        .foregroundStyle(Brand.orange)
                }
            }
        }
        .padding(10)
        .background(Brand.surface, in: RoundedRectangle(cornerRadius: 8))
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let text = note.trimmed
        let ok = await app.perform(success: "Đã thêm bằng chứng.") {
            try await app.api.addEvidence(disputeId: dispute.id, evidenceUrls: parsed, note: text.isEmpty ? nil : text)
        }
        if ok {
            urls = ""
            note = ""
            adding = false
            await onChanged()
        }
    }
}

/// Danh sách bằng chứng: link mở được, kèm ghi chú nếu có.
private struct EvidenceList: View {
    let evidence: [[String: JSONValue]]

    var body: some View {
        if evidence.isEmpty {
            Text("Chưa có bằng chứng.")
                .font(.caption)
                .foregroundStyle(Brand.muted)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(evidence.enumerated()), id: \.offset) { _, item in
                    let note = item["note"]?.stringValue.map { " — \($0)" } ?? ""
                    if let link = item["url"]?.stringValue, let url = URL(string: link), url.scheme == "https" {
                        Link(link + note, destination: url)
                            .font(.caption)
                            .lineLimit(2)
                    } else {
                        Text((item["url"]?.stringValue ?? "Bằng chứng") + note)
                            .font(.caption)
                            .foregroundStyle(Brand.muted)
                    }
                }
            }
        }
    }
}

private struct OpenDisputeForm: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let orderId: String
    let types: [String]
    let onOpened: () async -> Void

    @State private var type = ""
    @State private var description = ""
    @State private var urls = ""
    @State private var busy = false

    init(orderId: String, types: [String], onOpened: @escaping () async -> Void) {
        self.orderId = orderId
        self.types = types
        self.onOpened = onOpened
    }

    private var parsed: [String] { Validation.parseUrls(urls) }
    private var invalid: [String] { Validation.invalidEvidenceUrls(parsed) }
    private var valid: Bool {
        types.contains(type) && !description.trimmed.isEmpty && description.count <= 4000
            && invalid.isEmpty && parsed.count <= 20
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Loại", selection: $type) {
                        ForEach(types, id: \.self) { t in
                            Text(disputeTypeText(t)).tag(t)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Loại khiếu nại")
                } footer: {
                    if type == DisputeType.accountTakeover {
                        Text("Đơn đang chạy sẽ kết thúc ngay, tài khoản người thuê bị hạn chế và account game bị khóa để rà soát.")
                            .foregroundStyle(Brand.danger)
                    }
                }
                Section("Mô tả") {
                    TextField("Mô tả chuyện gì đã xảy ra", text: $description, axis: .vertical)
                        .lineLimit(4...10)
                }
                Section {
                    TextField("Mỗi dòng một link https", text: $urls, axis: .vertical)
                        .lineLimit(2...8)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Bằng chứng (tối đa 20)")
                } footer: {
                    if invalid.isEmpty {
                        Text("Hiện chưa hỗ trợ tải ảnh lên, hãy dán link ảnh hoặc video.")
                    } else {
                        Text("Chỉ nhận link https: \(invalid.joined(separator: ", "))")
                            .foregroundStyle(Brand.danger)
                    }
                }
            }
            .navigationTitle("Tạo khiếu nại")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button("Gửi") { Task { await submit() } }
                            .disabled(!valid)
                    }
                }
            }
            .onAppear {
                if type.isEmpty, let first = types.first { type = first }
            }
        }
    }

    private func submit() async {
        busy = true
        defer { busy = false }
        let ok = await app.perform(success: "Đã gửi khiếu nại.") {
            try await app.api.openDispute(orderId: orderId, type: type, description: description.trimmed, evidenceUrls: parsed)
        }
        if ok {
            await onOpened()
            dismiss()
        }
    }
}
