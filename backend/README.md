# Backend RentGG

Spring Boot 3.3.5, Java 21, PostgreSQL 16, Flyway. Mã nằm trong `src/main/java/com/rentgg/`.

## Cấu trúc module

Mỗi thư mục con của `src/main/java/com/rentgg/` là một module (19 module, kiểm bằng `ls`):

`approval`, `audit`, `catalog`, `config`, `deposit`, `dispute`, `gameaccount`, `helpdesk`, `identity`, `jobs`, `ledger`, `legal`, `listing`, `notification`, `order`, `payment`, `settings`, `shared`, `wallet`.

`docs/RentGG_System_Design_v1.2.md` §4.3 chỉ liệt kê 15 module. Bốn module `helpdesk`, `jobs`, `legal`, `settings` có trong mã nhưng không có trong bảng §4.3.

Quy tắc phụ thuộc giữa module nằm trong `docs/RentGG_System_Design_v1.2.md` §4.4. Có test `src/test/java/com/rentgg/ArchitectureTest.java`; nội dung chưa được đọc trong lượt này.

Mỗi module thường có `api/` (controller), `application/` (service), `domain/` (entity), `infrastructure/` (repository và tích hợp ngoài).

## Chạy local

Cần PostgreSQL 16 đang chạy. Cấu hình mặc định nằm trong `src/main/resources/application.yml`, cổng `8080`. `application.yml` không đặt profile mặc định, nên lệnh dưới đây không tự bật `dev`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Profile `dev` bật dữ liệu mẫu (`DevDataSeeder`), đồng hồ có thể tua (`/api/v1/dev/time/*`), và các endpoint chỉ dev. Profile `dev` không được đi cùng `prod` hoặc `staging`: `StartupSafetyCheck` sẽ từ chối khởi động.

Dữ liệu mẫu có tài khoản `*@rentgg.dev`. Mật khẩu là hằng `DevDataSeeder.DEV_PASSWORD` trong mã, chỉ dùng cho dev.

## Migration

Thư mục: `src/main/resources/db/migration/`. Có V1–V37. Không sửa migration đã có. Migration mới đánh số từ V38. Xem `CONTRIBUTING.md`.

## Test

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test \
TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev \
./mvnw -B test
```

Test có các lớp tích hợp `*IT.java` và các test kiến trúc, bảo mật (`AuthorizationCoverageTest`, `RbacIT`), tiền (`MoneyAndApprovalIT`, `DisputeIT`), và webhook PSP (`PayosWebhookIT`, `MomoWebhookIT`, `PaymentProvidersIT`). Số lượng test thay đổi theo thời điểm; không ghi số cố định ở đây.

## Build Docker

```bash
docker build -t rentgg-backend .
```

`Dockerfile` nằm trong thư mục này. CI (`.github/workflows/backend.yml`) có bước build tương tự.

## Vận hành

Xem `docs/RentGG_Operations_Runbook.md`: điều kiện khởi động, job định kỳ, sao lưu, và khôi phục.

## Tài liệu liên quan

- `docs/RentGG_API_Reference.md`: endpoint và quyền, lấy từ controller.
- `docs/RentGG_Data_Dictionary_Delta.md`: phần bổ sung cho từ điển dữ liệu.
- `docs/RentGG_System_Design_v1.2.md`: thiết kế.
