# RentGG (Phase 0 — MVP)

Marketplace cho thuê account game, xây theo **System Design v1.2** (`docs/RentGG_System_Design_v1.2.md`) và **Use Case v8** (`docs/RentGG_Use_Cases_China_Model_v8.md`).

## Cấu trúc thư mục
```
RentGG/
├── backend/        Spring Boot API (Java 21, Maven, Dockerfile, mvnw)
├── frontend/       Giao diện React + Vite (cài đặt và chạy riêng)
├── docs/           Tài liệu thiết kế và checklist
├── docker-compose.yml   PostgreSQL + backend
└── .env.example    Biến môi trường (copy thành .env ở thư mục gốc)
```

Backend: Java 21 · Spring Boot 3.3 · PostgreSQL 16 (+ `btree_gist`) · Flyway · ShedLock · Bucket4j · springdoc · Micrometer/Prometheus.

## Chạy nhanh

### Cách 1 — Docker Compose (từ thư mục gốc)
```bash
cp .env.example .env
docker compose up --build
```
API: http://localhost:8080 · Swagger UI: http://localhost:8080/swagger-ui.html

### Cách 2 — Chạy backend trực tiếp (cần PostgreSQL 16 có DB `rentgg`, user `rentgg`/`rentgg_dev`)
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
Đổi port: biến môi trường `SERVER_PORT=8081` (kèm `RENTGG_PUBLIC_BASE_URL=http://localhost:8081` để link thanh toán giả lập đúng).

### Dữ liệu mẫu (chỉ profile `dev`)
`DevDataSeeder` tạo sẵn các user `admin@`, `admin2@`, `support@`, `finance@`, `owner@`, `renter@` (đuôi `@rentgg.dev`, mật khẩu ghi trong `DevDataSeeder.DEV_PASSWORD`), một game `ALLOWED`, một game account đã xác minh và một listing `ACTIVE` (30.000đ/giờ, cọc 200.000đ, tự nhận đơn).

Luồng thử bằng tay: đăng nhập renter → `GET /api/v1/listings/search` → `POST /api/v1/orders` (header `Idempotency-Key`) → `POST /orders/{id}/pay` → gọi `checkoutUrl` (`/api/v1/dev/payments/{intentId}/simulate-success`). Admin có thể chạy mọi job ngay bằng `POST /api/v1/dev/jobs/run-all`.

## Frontend
Giao diện React + Vite nằm trong `frontend/`, cài đặt và chạy độc lập với backend:
```bash
cd frontend
npm install
npm run dev     # http://localhost:5173, proxy /api tới backend
```
Chi tiết xem `frontend/README.md`.

## Kiểm thử
```bash
cd backend
# Có Docker: Testcontainers tự dựng PostgreSQL 16
./mvnw test
# Không có Docker: trỏ tới một DB trống (test sẽ clean + migrate DB này)
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev ./mvnw test
```
31 test, bao phủ các kịch bản bắt buộc T1–T18 của §18.2 (đặt trùng đồng thời, ledger cân bằng theo ví dụ §7.4/§7.5, cọc riêng từng đơn, rút tiền song song, dual approval, cửa sổ xem credentials, 3 end mode, hủy đơn, khiếu nại, chiếm account, fail-closed theo game, job chạy lặp/song song) + test kiến trúc (ArchUnit, quy tắc §4.4) + unit test (tiền, AES-GCM, HMAC).

## Cấu trúc
`backend/src/main/java/com/rentgg/<module>/{api,application,domain,infrastructure}` — modular monolith theo §4.3:
`identity`, `catalog`, `gameaccount`, `listing`, `order`, `payment`, `ledger`, `deposit`, `wallet`, `dispute`, `approval`, `notification`, `audit`, `shared`, `config`, `jobs`.

Migration: `backend/src/main/resources/db/migration/V1..V7` (exclusion constraint chống đặt trùng, trigger append-only cho ledger/audit, constraint trigger cân bằng bút toán, CHECK four-eyes cho approval).

## Khác biệt / bổ sung nhỏ so với tài liệu
- Thêm `POST /orders/{id}/otp-note` (owner gửi mã OTP qua đơn, tự hết hạn sau 10 phút) — kênh "ghi chú trong đơn" mà UC-08 nhắc tới cho game `OWNER_ASSISTED_OTP`.
- Thêm `GET /admin/ledger/reconciliation` (truy vấn đối soát §16.3) và `GET /admin/ledger/orders/{id}`.
- Refund lỗi được job `retryFailedRefunds` thử lại có backoff (thay vì giữ event outbox chưa phát).
- Khiếu nại của người thuê (`RENTER_*`) chỉ mở được khi đơn còn `IN_RENTAL`, đúng bảng §6.5.

## Chưa làm (theo lộ trình)
PSP thật (quyết định D1 — chỉ cần thêm một `PaymentGateway`), MFA cho staff, upload bằng chứng, các UC P1/P2. Các tham số theo game (cọc, hold, handover mode…) cần điền từ `docs/RentGG_Validation_Checklist.md` qua `POST /api/v1/admin/games`.
