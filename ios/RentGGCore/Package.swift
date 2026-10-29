// swift-tools-version:5.9
import PackageDescription

// Phần logic không phụ thuộc giao diện: model, API client, lưu token, format, quy tắc nghiệp vụ.
// Build và test được cả trên Linux (`swift test`), nên kiểm chứng được mà không cần Xcode.
let package = Package(
    name: "RentGGCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RentGGCore", targets: ["RentGGCore"]),
    ],
    targets: [
        .target(name: "RentGGCore"),
        .testTarget(name: "RentGGCoreTests", dependencies: ["RentGGCore"]),
    ]
)
