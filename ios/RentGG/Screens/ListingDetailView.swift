import RentGGCore
import SwiftUI

// Chi tiết listing và đặt thuê (tương đương frontend/src/pages/ListingDetailPage.tsx).

struct ListingDetailView: View {
    @EnvironmentObject private var app: AppState
    let listingId: String
    @State private var state: LoadState<ListingDetail> = .loading

    init(listingId: String) {
        self.listingId = listingId
    }

    var body: some View {
        LoadStateView(state: state, retry: { Task { await load() } }) { detail in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    header(detail.listing)
                    BookingCard(detail: detail, isOwner: app.me?.id == detail.listing.ownerId)
                    policyCard(detail)
                    if let attributes = detail.listing.attributes, !attributes.isEmpty {
                        attributesCard(attributes)
                    }
                    busyCard(detail.busyPeriods ?? [])
                }
                .padding(12)
            }
            .refreshable { await load() }
        }
        .background(Brand.surface)
        .orangeNav("Chi tiết account")
        .task { await load() }
    }

    private func header(_ listing: Listing) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Text(listing.title)
                    .font(.title3.bold())
                PriceText(perHour: listing.pricePerHour)
                HStack(spacing: 4) {
                    TagChip(text: "Cọc \(vnd(listing.depositAmount))")
                    if listing.autoAccept == true {
                        TagChip(text: "Tự nhận đơn", color: Brand.green)
                    }
                }
                if let description = listing.description, !description.isEmpty {
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(Brand.ink)
                }
            }
        }
    }

    private func policyCard(_ detail: ListingDetail) -> some View {
        let policy = detail.policy
        return SectionCard("Quy định thuê") {
            InfoRow(label: "Thời lượng", value: "\(durationText(minutes: policy.minDurationMinutes ?? 60)) – \(durationText(minutes: policy.maxDurationMinutes ?? 1440))")
            InfoRow(label: "Cách bàn giao", value: handoverText(policy.handoverMode))
            InfoRow(label: "Kết thúc thuê", value: endModeText(detail.listing.endMode))
            if let free = policy.renterFreeCancelMinutes {
                InfoRow(label: "Hủy miễn phí", value: "Trong \(free) phút đầu")
            }
            if let window = policy.complaintWindowMinutes, window > 0 {
                InfoRow(label: "Thời gian khiếu nại", value: "\(window) phút sau khi nhận")
            }
            InfoRow(label: "Độ tuổi tối thiểu", value: "\(policy.minAge ?? 18)+")
        }
    }

    private func attributesCard(_ attributes: [String: JSONValue]) -> some View {
        SectionCard("Thông tin account") {
            ForEach(attributes.keys.sorted(), id: \.self) { key in
                InfoRow(label: key, value: attributes[key]?.display ?? "")
            }
        }
    }

    private func busyCard(_ busy: [BusyPeriod]) -> some View {
        SectionCard("Lịch đã bận") {
            if busy.isEmpty {
                Text("Chưa có lịch đặt nào.")
                    .font(.subheadline)
                    .foregroundStyle(Brand.muted)
            }
            ForEach(Array(busy.enumerated()), id: \.offset) { _, period in
                Text("\(fmtTime(period.startAt)) → \(fmtTime(period.endAt))")
                    .font(.subheadline)
                    .foregroundStyle(Brand.muted)
            }
        }
    }

    private func load() async {
        if state.value == nil { state = .loading }
        do {
            state = .ready(try await app.api.listing(id: listingId))
        } catch {
            state = .error(errorText(error))
        }
    }
}

/// Chọn giờ bắt đầu, thời lượng và đặt đơn. Đặt xong chuyển sang chi tiết đơn để thanh toán.
private struct BookingCard: View {
    @EnvironmentObject private var app: AppState
    let detail: ListingDetail
    let isOwner: Bool

    @State private var start = Booking.defaultStart()
    @State private var duration = 60
    @State private var busy = false
    @State private var createdOrderId: String?

    init(detail: ListingDetail, isOwner: Bool) {
        self.detail = detail
        self.isOwner = isOwner
    }

    private var options: [Int] {
        Booking.durationOptions(min: detail.policy.minDurationMinutes ?? 60, max: detail.policy.maxDurationMinutes ?? 1440)
    }

    private var conflict: BusyPeriod? {
        Booking.overlapping(start: start, durationMinutes: duration, busy: detail.busyPeriods ?? [])
    }

    var body: some View {
        SectionCard("Đặt thuê") {
            if detail.bookable != true {
                Text("Account này hiện chưa nhận đơn.")
                    .font(.subheadline)
                    .foregroundStyle(Brand.muted)
            } else if isOwner {
                Text("Đây là listing của bạn.")
                    .font(.subheadline)
                    .foregroundStyle(Brand.muted)
            } else {
                DatePicker("Bắt đầu", selection: $start, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                    .tint(Brand.orange)

                Picker("Thời lượng", selection: $duration) {
                    ForEach(options, id: \.self) { minutes in
                        Text(durationText(minutes: minutes)).tag(minutes)
                    }
                }
                .tint(Brand.orange)

                Divider()
                InfoRow(label: "Kết thúc", value: fmtTime(isoString(start.addingTimeInterval(TimeInterval(duration * 60)))))
                InfoRow(label: "Tiền thuê (ước tính)", value: vnd(Booking.estimatedRent(pricePerHour: detail.listing.pricePerHour, durationMinutes: duration)))
                InfoRow(label: "Tiền cọc (hoàn lại)", value: vnd(detail.listing.depositAmount))

                if let conflict {
                    Text("Trùng lịch đã bận \(fmtTime(conflict.startAt)) → \(fmtTime(conflict.endAt)). Hãy chọn giờ khác.")
                        .font(.footnote)
                        .foregroundStyle(Brand.danger)
                }

                PrimaryButton(title: "Đặt thuê", enabled: conflict == nil && start > Date(), loading: busy) {
                    Task { await book() }
                }
                .padding(.top, 4)

                Text("Số tiền chính xác hiển thị ở bước thanh toán.")
                    .font(.caption)
                    .foregroundStyle(Brand.muted)
            }
        }
        .onAppear {
            if !options.contains(duration), let first = options.first { duration = first }
        }
        .navigationDestination(item: $createdOrderId) { id in
            OrderDetailView(orderId: id)
        }
    }

    private func book() async {
        busy = true
        defer { busy = false }
        var orderId: String?
        let ok = await app.perform(success: "Đã tạo đơn. Hãy thanh toán để giữ chỗ.") {
            orderId = try await app.api.createOrder(listingId: detail.listing.id, startAt: start, durationMinutes: duration).id
        }
        if ok { createdOrderId = orderId }
    }
}
