import RentGGCore
import SwiftUI
import UIKit

/// Nhãn nhỏ có nền nhạt (tương đương Tag bên Android).
struct TagChip: View {
    let text: String
    var color: Color = Brand.orange

    var body: some View {
        Text(text)
            .font(.caption2)
            .lineLimit(1)
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 4))
    }
}

struct StatusChip: View {
    let label: StatusLabel

    var body: some View {
        TagChip(text: label.text, color: label.tone.color)
    }
}

/// Giá kiểu "30.000đ/giờ": số lớn màu đỏ cam, đơn vị nhỏ.
struct PriceText: View {
    let perHour: Int

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 2) {
            Text(vnd(perHour))
                .font(.headline.bold())
                .foregroundStyle(Brand.priceRed)
            Text("/giờ")
                .font(.caption2)
                .foregroundStyle(Brand.priceRed)
        }
    }
}

/// Ô vuông chữ cái đầu có màu theo tên game. Dùng hash ổn định (không dùng Hasher, vì Swift
/// xáo trộn kết quả giữa các lần chạy) để cùng một game luôn có cùng màu.
struct GameTile: View {
    let name: String
    var size: CGFloat = 44

    private static let palette: [Color] = [
        Color(hex: 0xFF6A00), Color(hex: 0x2F7DF6), Color(hex: 0x8E44AD),
        Color(hex: 0x1FA15C), Color(hex: 0xE91E63), Color(hex: 0x00A8A8),
    ]

    var body: some View {
        let sum = name.unicodeScalars.reduce(0) { $0 &* 31 &+ Int($1.value) }
        let color = Self.palette[abs(sum % Self.palette.count)]
        Text(String(name.trimmed.prefix(1)).uppercased())
            .font(.system(size: size / 2.2, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct EmptyStateView: View {
    let text: String

    var body: some View {
        Text(text)
            .foregroundStyle(Brand.muted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(48)
    }
}

/// Khung tải dữ liệu: đang tải, lỗi (có nút thử lại) hoặc nội dung.
struct LoadStateView<T, Content: View>: View {
    let state: LoadState<T>
    let retry: () -> Void
    let content: (T) -> Content

    init(state: LoadState<T>, retry: @escaping () -> Void, @ViewBuilder content: @escaping (T) -> Content) {
        self.state = state
        self.retry = retry
        self.content = content
    }

    var body: some View {
        switch state {
        case .loading:
            ProgressView()
                .tint(Brand.orange)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            VStack(spacing: 12) {
                Text(message)
                    .foregroundStyle(Brand.danger)
                    .multilineTextAlignment(.center)
                Button("Thử lại", action: retry)
                    .buttonStyle(.borderedProminent)
                    .tint(Brand.orange)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready(let value):
            content(value)
        }
    }
}

/// Dòng "nhãn — giá trị" dùng chung cho các màn chi tiết.
struct InfoRow: View {
    let label: String
    let value: String
    var emphasized: Bool = false

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(Brand.muted)
            Spacer(minLength: 12)
            Text(value)
                .font(emphasized ? .subheadline.bold() : .subheadline)
                .foregroundStyle(emphasized ? Brand.priceRed : Brand.ink)
                .multilineTextAlignment(.trailing)
        }
    }
}

/// Thông tin bí mật (tài khoản, mật khẩu, OTP): chữ đơn cách, có nút sao chép,
/// và bị che khi app chụp màn hình đa nhiệm.
struct SecretRow: View {
    let label: String
    let value: String
    @State private var copied = false

    init(label: String, value: String) {
        self.label = label
        self.value = value
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(Brand.muted)
                Text(value)
                    .font(.body.monospaced())
                    .textSelection(.enabled)
                    .privacySensitive()
            }
            Spacer()
            Button {
                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: value]], options: [
                    .expirationDate: Date().addingTimeInterval(120),
                    .localOnly: true,
                ])
                copied = true
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .foregroundStyle(Brand.orange)
            }
            .accessibilityLabel("Sao chép \(label)")
        }
        .padding(10)
        .background(Brand.surface, in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Thanh tiêu đề nền cam, chữ trắng (tương đương OrangeTopBar bên Android).
struct OrangeNavModifier: ViewModifier {
    let title: String

    func body(content: Content) -> some View {
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Brand.orange, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

extension View {
    func orangeNav(_ title: String) -> some View {
        modifier(OrangeNavModifier(title: title))
    }
}
