import RentGGCore
import SwiftUI

// Bảng màu giống Android (ui/Theme.kt): cam nhấn, nền xám nhạt, thẻ trắng bo góc, giá màu đỏ cam.
enum Brand {
    static let orange = Color(hex: 0xFF6A00)
    static let orangeSoft = Color(hex: 0xFFF1E6)
    static let priceRed = Color(hex: 0xFF4D2E)
    static let ink = Color(hex: 0x222222)
    static let muted = Color(hex: 0x8A8A93)
    static let surface = Color(hex: 0xF6F6F8)
    static let green = Color(hex: 0x1FA15C)
    static let blue = Color(hex: 0x2F7DF6)
    static let amber = Color(hex: 0xD48806)
    static let danger = Color(hex: 0xD93025)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

extension Tone {
    var color: Color {
        switch self {
        case .accent: Brand.orange
        case .info: Brand.blue
        case .success: Brand.green
        case .warning: Brand.amber
        case .danger: Brand.danger
        case .neutral: Brand.muted
        }
    }
}

/// Thẻ trắng bo góc, dùng chung cho danh sách và các khối thông tin.
struct Card<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.05), radius: 2, y: 1)
    }
}

/// Khối có tiêu đề bên trong thẻ.
struct SectionCard<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Text(title).font(.headline)
                content
            }
        }
    }
}

enum ButtonKind {
    case primary, secondary, danger
}

/// Nút bo tròn toàn chiều ngang, có trạng thái đang xử lý.
struct PrimaryButton: View {
    let title: String
    var kind: ButtonKind = .primary
    var enabled: Bool = true
    var loading: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if loading {
                    ProgressView().tint(kind == .secondary ? Brand.orange : .white)
                } else {
                    Text(title).fontWeight(.bold)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .foregroundStyle(kind == .secondary ? Brand.orange : .white)
            .background(background, in: Capsule())
            .overlay {
                if kind == .secondary { Capsule().stroke(Brand.orange, lineWidth: 1) }
            }
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.45)
        .disabled(!enabled || loading)
    }

    private var background: Color {
        switch kind {
        case .primary: Brand.orange
        case .secondary: Color.white
        case .danger: Brand.danger
        }
    }
}
