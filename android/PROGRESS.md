# Tiến trình app Android

Cập nhật tự động trong lúc làm. Mới nhất ở trên.

## Nhật ký
- [x] Build **release** (R8) thành công sau khi thêm rule `-dontwarn` cho Tink; `app-release-unsigned.apk` ~2 MB. Chưa chạy thử bản release nên rule giữ lớp serialization chưa được kiểm chứng lúc chạy.
- [x] Chọn ngày giờ bắt đầu tùy ý khi đặt đơn (DatePicker + TimePicker, chặn giờ quá khứ).
- [x] Bấm thông báo có `orderId` thì mở thẳng đơn liên quan.
- [x] Lint chạy xong, không có lỗi chặn (còn cảnh báo cập nhật phiên bản thư viện, icon monochrome, cleartext của build debug).
- [x] Unit test `ApiClientTest` 5/5 qua: gắn Bearer, giải mã bỏ qua khóa lạ, lỗi backend → `ApiException`, 401 → refresh một lần rồi thử lại, refresh hỏng → xóa phiên và báo hết hạn, `Idempotency-Key` chỉ gửi khi yêu cầu.
- [x] Build debug thành công ngay lần đầu (`app-debug.apk`, ~18 MB).
- [x] Cài môi trường: Android SDK (platform 35, build-tools 35.0.0 + 34.0.0, platform-tools) tại `%LOCALAPPDATA%\Android\Sdk`, Gradle 8.9 tại `C:\Users\defu\Android\gradle-8.9`, `ANDROID_HOME` ở mức người dùng, `gradlew` đã sinh. Đã chấp nhận license SDK theo yêu cầu cài môi trường.
- [x] Viết mã nguồn Compose: đăng nhập, trang chủ, chi tiết, đơn, ví, chủ account, thông báo.

## Giới hạn môi trường
- Máy báo `VirtualizationFirmwareEnabled = False` và không có Docker/PostgreSQL → **chưa chạy được emulator và backend** để thử E2E. Muốn thử cần bật ảo hóa trong BIOS (rồi cài system image + emulator) hoặc dùng điện thoại thật.

## Còn lại
- [ ] Thử E2E với backend dev trên emulator/điện thoại thật.
- [ ] Ký release (keystore), đặt `API_BASE_URL` thật.
- [ ] Ảnh game/listing, tìm theo từ khóa phía server, thanh toán thật, push notification (chờ backend).
