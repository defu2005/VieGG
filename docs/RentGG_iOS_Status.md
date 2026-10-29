# RentGG — Tình trạng App iOS

**Bắt đầu:** 2026-10-26 · **Cập nhật:** 2026-10-27 · **Rà soát:** 2026-10-29
**Mã nguồn:** `ios/` (SwiftUI, iOS 17+, project sinh bằng XcodeGen) · hướng dẫn: `ios/README.md`
**Đối chiếu với:** `frontend/` (luồng người dùng đầy đủ) và API trong `backend/`

File này được cập nhật sau mỗi phần hoàn thành.

---

## 1. Môi trường phát triển

| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| Máy phát triển hiện tại | Windows 11, không có quyền admin | Xcode và iOS Simulator chỉ chạy trên macOS, không cài được ở đây |
| WSL2 + Ubuntu 24.04 | Đã cài | `wsl -d Ubuntu-24.04` |
| Swift toolchain (Linux) | Đã cài: Swift 6.4.1 qua `swiftly` | Build và test `RentGGCore`, kiểm tra cú pháp các file SwiftUI (`swiftc -parse`) |
| Backend để test tích hợp | Đã dựng trong Ubuntu: PostgreSQL 16, OpenJDK 21, Maven 3.8 | Build từ bản sao `/root/rentgg-backend` (không đụng `backend/target` của repo), chạy profile `dev` ở `localhost:8080` |
| Build toàn app SwiftUI | **Chưa chạy** | Cần macOS: máy Mac có Xcode 15.3+, hoặc GitHub Actions `macos-15` (workflow `.github/workflows/ios.yml`, cần đưa repo lên GitHub) |

## 2. Kế hoạch và tiến độ

| # | Phần | Trạng thái |
|---|---|---|
| 1 | Khung app: đăng nhập, đăng ký, 4 tab, xem listing và đơn | Xong |
| 2 | Tách `RentGGCore` (Swift package) để test trên Linux, sửa đọc thời gian micro giây, sửa `SWIFT_VERSION` | Xong |
| 3 | Đặt đơn, thanh toán (giả lập ở dev), duyệt/từ chối/hủy, xác nhận bàn giao, xem credentials, OTP | Xong (logic đã test với backend; UI chờ build macOS) |
| 4 | Ví và rút tiền, account game (thêm, đổi mật khẩu, lấy lại), listing của tôi (tạo, sửa, gửi duyệt, tạm dừng), hồ sơ và KYC | Xong (logic đã test với backend; UI chờ build macOS) |
| 5 | Khiếu nại: mở, thêm bằng chứng, xem kết quả | Xong (logic đã test với backend; UI chờ build macOS) |
| 6 | Kiểm chứng | 39 unit test + 6 test tích hợp đạt; 16/16 file app qua `swiftc -parse`; **chưa build bằng Xcode** |

Ngoài phạm vi app di động: các màn staff (`/admin/*`, `/support/disputes`, `/finance`) vẫn dùng trên web.

## 3. Kết quả kiểm chứng (2026-10-27)

| Kiểm tra | Kết quả | Phạm vi |
|---|---|---|
| `swift test` (unit) | 39/39 đạt | Client HTTP (refresh dùng chung, giữ `Idempotency-Key` khi gửi lại, xử lý lỗi/mạng/JSON hỏng), đọc thời gian, giải mã model, quy tắc thao tác, kiểm tra dữ liệu nhập, tiện ích đặt thuê |
| `swift test --filter IntegrationTests` với backend thật | 6/6 đạt | Đọc mọi endpoint chính; tự làm mới token hết hạn; sai mật khẩu; đặt rồi hủy đơn; chủ tạo/sửa listing và bị chặn khi ghi đè bằng `version` cũ; trọn vòng đời đơn trên `quick-game`: chủ đổi mật khẩu → đặt → thanh toán → CONFIRMED → chặn xem credentials trước giờ → IN_RENTAL → xem credentials → mở khiếu nại + thêm bằng chứng → xác nhận bàn giao → backend chặn khiếu nại sau đó |
| `swiftc -parse` các file SwiftUI | 16/16 đạt | Chỉ cú pháp; kiểu và API SwiftUI chưa được trình biên dịch kiểm tra |
| Build Xcode / chạy trên Simulator | Chưa chạy | Cần macOS |

## 4. Phát hiện trong lúc làm

- **Quy tắc khiếu nại ở web lệch backend.** `frontend/src/components/DisputesSection.tsx` hiện form khiếu nại cho người thuê khi đơn `IN_RENTAL` hoặc `ENDED`. Backend (`DisputeService.open`) chỉ nhận khi đơn `IN_RENTAL`, đã xem credentials (hạn khiếu nại đã bắt đầu), chưa quá hạn và **chưa xác nhận bàn giao**. Với chủ account, "báo thiệt hại" chỉ được khi `ENDED`. iOS đã làm theo backend (`OrderActions` trong `RentGGCore`, có test), và cảnh báo người thuê trước khi xác nhận bàn giao. Web đã được sửa tương tự ngày 2026-10-29.
- **Thời gian có micro giây.** Backend trả mốc thời gian dạng `…:00.123456Z`; bản đầu của app iOS hiển thị thành "—". Đã sửa và có test.
- **Đổi mật khẩu sau mỗi lần thuê.** Hết đơn, account chuyển `ROTATION_PENDING` và listing biến khỏi tìm kiếm cho đến khi chủ đổi mật khẩu. App hiện cảnh báo này ở màn Account cho thuê.
- **Android (rà soát 2026-10-29):** 6 màn hình mà `AppRoot.kt` import đã có file: `ListingDetailScreen.kt`, `OrderDetailScreen.kt`, `WalletScreen.kt`, và `OwnerScreens.kt` (chứa `MyListingsScreen`, `AddAccountScreen`, `CreateListingScreen`). Chưa kiểm chứng bằng build. Báo cáo cũ về việc thiếu file không còn đúng.
- **Maven wrapper** (`backend/mvnw`) không chạy được trong WSL khi checkout là CRLF ("Could not find Maven distribution directory"). Dùng `mvn` của hệ thống thì build được.

## 5. Việc còn lại

1. Build bằng Xcode (máy Mac hoặc GitHub Actions) và sửa lỗi biên dịch nếu có. Đây là bước kiểm chứng duy nhất còn thiếu cho phần UI.
2. Chạy app trên Simulator với backend dev, đi lại các luồng ở mục 3 bằng tay.
3. Trước khi phát hành: domain thật cho `API_BASE_URL` Release, Bundle ID, team ký, icon chính thức, cổng thanh toán thật.

## 6. Nhật ký

- **2026-10-26** — Khung app SwiftUI đầu tiên (phần 1).
- **2026-10-26** — Cài WSL Ubuntu 24.04 và Swift 6.4.1. Xcode/Simulator không cài được trên Windows.
- **2026-10-26** — Phần 2: tách `ios/RentGGCore` (model, `APIClient` có transport thay được, `TokenStore` Keychain/bộ nhớ, format, `OrderActions`/`ListingActions`, kiểm tra dữ liệu nhập, tiện ích đặt thuê). Sửa lỗi đọc thời gian có micro giây. Nhãn rút tiền khớp backend (`REQUESTED/PAID/FAILED`). Request bị 401 mà token đã được làm mới thì gửi lại ngay, không refresh lần hai. `swift test`: 38/38 đạt.
- **2026-10-26** — Phần 3: chi tiết listing có đặt thuê (chọn giờ, thời lượng theo bước 30 phút, cảnh báo trùng lịch bận, ước tính tiền), chi tiết đơn với đủ thao tác theo vai trò (thanh toán + giả lập ở bản Debug, duyệt/từ chối, xác nhận bàn giao, xem credentials có nút sao chép và tự xóa clipboard sau 2 phút, yêu cầu/gửi OTP, hủy có hỏi lại), khiếu nại (phần 5) gộp trong chi tiết đơn.
- **2026-10-26** — Phần 4: tab "Của tôi" (hồ sơ, KYC tùy chọn, xác nhận trước khi đăng xuất), Ví (số dư, rút tiền có kiểm tra số dư và số tài khoản 6–20 chữ số, lịch sử rút, cọc theo đơn), Account cho thuê (thêm account game, đổi mật khẩu, lấy lại có hỏi lại, tạo/sửa listing gửi kèm `version`, gửi duyệt, tạm dừng). Thêm icon app và màu nhấn (`Assets.xcassets`), `project.yml` liên kết `RentGGCore`, khai báo scheme `RentGG`, `SWIFT_VERSION` 5.0. Thêm workflow `.github/workflows/ios.yml` (macOS runner). `swiftc -parse` qua cả 16 file app.
- **2026-10-27** — Phần 6: dựng PostgreSQL 16 + JDK 21 + Maven trong Ubuntu, build và chạy backend dev. Thêm 6 test tích hợp. Test phát hiện quy tắc khiếu nại thật của backend (mục 4), đã sửa `OrderActions`, thêm cảnh báo trước khi xác nhận bàn giao, ẩn loại khiếu nại đang mở, cảnh báo hậu quả của "báo chiếm account". Kết quả: 39 unit + 6 tích hợp đạt.
- **2026-10-29** — Rà soát tài liệu với code: Android đã có đủ file màn hình (mục 4). Web đã sửa form khiếu nại cho khớp backend.
