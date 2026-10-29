# RentGG iOS

App iOS native (SwiftUI, iOS 17+) dùng chung backend `backend/` với web (`frontend/`) và Android (`android/`).
Tiến độ và nhật ký: `docs/RentGG_iOS_Status.md`.

## Chức năng
| Nhóm | Màn hình |
|---|---|
| Tài khoản | Đăng nhập, đăng ký (kiểm tra đủ 18 tuổi), hồ sơ, xác minh danh tính (tùy chọn), đăng xuất |
| Thuê | Trang chủ (tìm kiếm, lọc theo game, sắp xếp giá, "Tự nhận đơn"), chi tiết listing, đặt thuê (chọn giờ và thời lượng, cảnh báo trùng lịch bận, ước tính tiền) |
| Đơn | Danh sách "Tôi thuê" / "Tôi cho thuê", chi tiết đơn: thanh toán (bản Debug có nút giả lập), duyệt/từ chối, xác nhận bàn giao, xem credentials (sao chép, tự xóa clipboard sau 2 phút), yêu cầu/gửi OTP, hủy |
| Khiếu nại | Mở khiếu nại theo vai trò, thêm bằng chứng (link https), xem kết quả phân xử |
| Ví | Số dư, rút tiền, lịch sử rút, tiền cọc theo đơn |
| Chủ account | Thêm account game, đổi mật khẩu (rotation), lấy lại account, tạo/sửa listing, gửi duyệt, tạm dừng |
| Thông báo | Danh sách, lọc chưa đọc, đánh dấu đã đọc, số chưa đọc trên tab |

Màn dành cho staff (`/admin/*`, `/support/disputes`, `/finance`) vẫn dùng trên web.

## Cấu trúc
```
ios/
├── project.yml                  Định nghĩa project cho XcodeGen (sinh RentGG.xcodeproj)
├── RentGGCore/                  Swift package, không phụ thuộc giao diện, build và test được trên Linux
│   ├── Sources/RentGGCore/
│   │   ├── Models.swift         Read model của backend
│   │   ├── APIClient.swift      HTTP, Bearer, refresh dùng chung khi 401, Idempotency-Key
│   │   ├── Endpoints.swift      Mọi endpoint người dùng cuối cần
│   │   ├── TokenStore.swift     Keychain trên iOS, bộ nhớ khi test
│   │   ├── Format.swift         Tiền VND, thời gian ISO-8601 (kể cả micro giây), nhãn trạng thái
│   │   └── Rules.swift          Ai được làm gì với đơn/listing, kiểm tra dữ liệu nhập, tiện ích đặt thuê
│   └── Tests/RentGGCoreTests/   Unit test + test tích hợp với backend thật (tùy chọn)
└── RentGG/                      App SwiftUI
    ├── RentGGApp.swift          Điểm vào, chọn màn đăng nhập hay app chính, snackbar
    ├── Assets.xcassets          Icon app, màu nhấn
    ├── UI/                      AppState, theme, thành phần dùng chung
    └── Screens/                 Các màn hình
```

## Build trên máy Mac
Cần Xcode 15.3 trở lên và [XcodeGen](https://github.com/yonaskolb/XcodeGen).
```bash
brew install xcodegen
cd ios
xcodegen generate
open RentGG.xcodeproj
```
`RentGG.xcodeproj` và `RentGG/Info.plist` được sinh ra, không commit (đã có trong `ios/.gitignore`).

- **Simulator**: chọn một iPhone rồi Run. Bản Debug trỏ `http://localhost:8080/api/v1/`, simulator dùng chung localhost với máy Mac.
- **iPhone thật**: đổi `API_BASE_URL` của Debug trong `project.yml` sang IP LAN của máy chạy backend (ví dụ `http://192.168.1.10:8080/api/v1/`), chạy lại `xcodegen generate`, chọn team ký trong Xcode.
- **Tài khoản dev**: `renter@rentgg.dev`, `owner@rentgg.dev`… mật khẩu là `DevDataSeeder.DEV_PASSWORD` (xem `docs/RentGG_Dev_Seed.md`).

## Build không cần Mac (GitHub Actions)
Workflow `.github/workflows/ios.yml` chạy trên máy `macos-15` của GitHub: test `RentGGCore`, sinh project, build cho iOS Simulator (không ký). Chạy khi push thay đổi trong `ios/` hoặc bấm "Run workflow".

## Test `RentGGCore` trên Windows (WSL) hoặc Linux
Cần Swift 5.9 trở lên (máy dev hiện tại: Swift 6.4.1 trong WSL Ubuntu 24.04, cài bằng `swiftly`).
```bash
cd ios/RentGGCore
swift test --disable-swift-testing
```
Gói chỉ dùng XCTest. Trên Linux với Swift 6.4, bộ chạy swift-testing có lúc treo khi dùng `--filter`, nên tắt nó đi.

Test tích hợp gọi backend thật (profile `dev`, có dữ liệu mẫu) và chạy trọn vòng đời một đơn trên `quick-game` (khoảng 2 phút):
```bash
RENTGG_E2E_BASE_URL=http://localhost:8080/api/v1/ swift test --disable-swift-testing --filter IntegrationTests
```
- Mỗi lần chạy tạo thêm đơn, khiếu nại và listing nháp trong DB dev.
- Backend giới hạn 5 lần đăng nhập/phút và 20 lần/giờ cho mỗi email; một lần chạy dùng 3 lần cho `renter@`.
- Nếu đơn chạy thử trước đó trên "Account demo nhanh 1" còn đang thuê, chờ nó kết thúc (5 phút) rồi chạy lại.

## Cấu hình
| Cấu hình | `API_BASE_URL` | Ghi chú |
|---|---|---|
| Debug | `http://localhost:8080/api/v1/` | HTTP chỉ được phép tới localhost/IP LAN (`NSAllowsLocalNetworking`), có nút giả lập thanh toán |
| Release | `https://api.rentgg.example/api/v1/` | Đổi sang domain thật trước khi phát hành |

## Trước khi phát hành
- Đổi `api.rentgg.example` thành domain thật, đặt Bundle ID và team ký.
- Thay icon tạm (`Assets.xcassets/AppIcon.appiconset`) bằng icon chính thức nếu có.
- Thanh toán thật: backend hiện chỉ có cổng giả lập (`FAKE`). Khi có PSP thật, `checkoutUrl` (https) được mở bằng trình duyệt hệ thống.
