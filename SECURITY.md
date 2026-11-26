# Bảo mật

## Báo lỗ hổng

Repo chưa có kênh báo lỗ hổng chính thức. Chủ dự án cần điền kênh trước khi mở repo ra ngoài. Đừng mở issue công khai cho lỗ hổng có thể khai thác.

## Những điều đã có trong mã

Các điều dưới đây đọc từ mã và cấu hình, kèm đường dẫn. Không phải kiểm toán bảo mật.

- **Từ chối khởi động khi cấu hình không an toàn.** `backend/src/main/java/com/rentgg/config/StartupSafetyCheck.java` không cho ứng dụng chạy trên `staging` hoặc `prod` nếu còn khóa dev, secret mặc định, placeholder, URL không dùng HTTPS, hoặc thiếu biến của PSP đã chọn. Chi tiết trong `docs/RentGG_Operations_Runbook.md` mục 1.
- **Xác thực hai bước cho nhân viên.** `RENTGG_STAFF_MFA_REQUIRED` phải là `true` trên `staging` và `prod`. Kiểm thử tay `A22` ghi đăng nhập không có mã bị từ chối.
- **Quyền theo vai trò.** Mỗi endpoint `/admin`, `/support`, `/finance`, `/staff` có `@PreAuthorize`, được kiểm bởi `backend/src/test/java/com/rentgg/AuthorizationCoverageTest.java`. Test chỉ kiểm tra có annotation, không kiểm tra biểu thức đúng. Xem `docs/RentGG_API_Reference.md`.
- **Webhook PSP.** `POST /api/v1/payments/webhook/{provider}` không cần đăng nhập và xác thực chữ ký trong `PaymentGateway.verifyCallback`. Chữ ký của từng provider nằm trong `backend/src/main/java/com/rentgg/payment/infrastructure/psp/`.
- **Thông tin đăng nhập của account game.** Người thuê chỉ xem trong cửa sổ thuê và có giới hạn lượt xem (`docs/RentGG_System_Design_v1.2.md` §12.4). Giá trị giới hạn trong `application.yml` chưa đối chiếu.
- **Endpoint chỉ dev.** Các controller có `@Profile("dev")` hoặc `@Profile({"dev","test"})` không chạy trên `prod`. Danh sách trong `docs/RentGG_API_Reference.md`, cột "Chỉ dev".

## Không commit bí mật

Không đưa giá trị thật vào repo: `.env`, `deploy/.env.prod`, keystore, khóa PSP, khóa S3/R2, mật khẩu DB thật. Dùng `deploy/.env.prod.example` làm mẫu và giá trị mock trong test. Nếu một bí mật đã lộ vào git, xoay khóa ở nơi cấp khóa trước, rồi mới xử lý lịch sử git.

Mật khẩu của dữ liệu dev nằm trong mã (`DevDataSeeder.DEV_PASSWORD`). Đó là dữ liệu dev, không dùng cho môi trường thật.

## Chưa làm

- Chưa có kiểm toán bảo mật bên ngoài.
- Chưa kiểm chứng quyền ở tầng API bằng cách gọi thật từng vai trò. Kiểm thử tay có một số kết quả (`docs/RentGG_Manual_Test_Report.md`).
- Chưa có chính sách đầy đủ cho việc xoay khóa, thu hồi phiên, và xử lý sự cố.
