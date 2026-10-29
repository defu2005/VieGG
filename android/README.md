# RentGG — Ứng dụng Android

Ứng dụng di động cho người thuê và chủ account, gọi cùng API `/api/v1` với `frontend/`.
Kotlin · Jetpack Compose (Material 3) · OkHttp · kotlinx.serialization · minSdk 26.

Giao diện lấy cảm hứng từ các sàn thuê account Trung Quốc (租号玩…): thanh tìm kiếm nền cam, lưới game ở đầu trang chủ, thẻ account có thẻ nhãn (tự nhận đơn, cọc, rank…) và giá đỏ cam "đ/giờ", thanh điều hướng dưới (Trang chủ · Đơn thuê · Thông báo · Của tôi).

## Chạy thử
1. Chạy backend profile `dev` (xem README gốc), cổng 8080.
2. Mở thư mục `android/` bằng Android Studio (Ladybug trở lên, JDK 17), để Studio sync Gradle.
3. Chạy trên emulator: `10.0.2.2:8080` đã được cấu hình sẵn cho build debug. Với điện thoại thật, đổi `API_BASE_URL` (build type `debug`) trong `app/build.gradle.kts` sang IP LAN của máy chạy backend.
4. Đăng nhập bằng `renter@rentgg.dev` hoặc `owner@rentgg.dev` (mật khẩu: `DevDataSeeder.DEV_PASSWORD`).

Build debug có nút "Giả lập thanh toán (dev)" ở màn chi tiết đơn (gọi `/dev/payments/{id}/simulate-success`); build release không có.

## Đã có
| Nhóm | Màn hình |
|---|---|
| Tài khoản | Đăng nhập, đăng ký, hồ sơ, đăng xuất. Token lưu bằng EncryptedSharedPreferences, tự refresh khi 401 |
| Người thuê | Trang chủ (lưới game, tìm theo tên, sắp xếp giá, lọc tự nhận đơn), chi tiết account (chọn giờ bắt đầu, thời lượng, quy định, lịch bận), đặt đơn |
| Đơn | Danh sách (tôi thuê / tôi cho thuê), thanh toán, hủy, duyệt hoặc từ chối (chủ), xem tài khoản đăng nhập (chặn chụp màn hình), yêu cầu và gửi OTP, xác nhận nhận account, khiếu nại |
| Chủ account | Thêm account game, tạo listing, gửi duyệt, tạm dừng, lấy lại account |
| Ví | Số dư, rút tiền, lịch sử rút, cọc theo đơn |
| Thông báo | Danh sách, đánh dấu đã đọc, huy hiệu chưa đọc |

Không có màn quản trị (ADMIN/SUPPORT/FINANCE): dùng web.

## Chưa làm / lưu ý
- Đã build được debug và release, unit test và lint chạy qua (`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`), nhưng **chưa chạy thử trên thiết bị/emulator với backend thật**. Xem `PROGRESS.md`.
- Chưa có ảnh game/listing (backend chưa có), nên dùng ô chữ cái màu thay icon.
- Tìm kiếm theo tên lọc phía client trên 50 kết quả đầu; backend chưa có tham số tìm theo từ khóa hay sắp xếp.
- Chưa có thanh toán thật (backend mới có gateway giả), chưa có push notification.
- `API_BASE_URL` của build release là chỗ giữ chỗ (`https://api.rentgg.example`), cần đổi trước khi phát hành.
