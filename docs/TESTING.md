# Kiểm thử

Ngày kiểm tra **2026-11-21**. Số liệu đếm từ file trong repo; số lần test pass không ghi ở đây, vì cần chạy lại để có con số hiện tại.

## Tổng quan

| Phạm vi | Công cụ | Nơi chạy | Trạng thái |
|---|---|---|---|
| Backend unit và integration | JUnit 5, Spring Boot Test, ArchUnit, MockWebServer, S3Proxy | CI (`backend.yml`) và máy dev | IMPLEMENTED |
| Web typecheck và build | `tsc`, Vite | CI (`web.yml`) | IMPLEMENTED |
| Caddyfile | `caddy validate` | CI (`web.yml`) | IMPLEMENTED |
| iOS unit | `swift test --disable-swift-testing` | CI (`ios.yml`, macOS) | IMPLEMENTED |
| Android | Không có test tự động trong repo đã kiểm tra | — | UNKNOWN |
| E2E trình duyệt | Playwright | Thủ công, không trong CI | PARTIAL: xem mục E2E |
| Kiểm thử trên thiết bị hoặc simulator thật | — | — | PLANNED |

## Số lượng file test (backend)

Đếm theo tên file, ngày 2026-11-21:

- 20 file `*Test.java` (unit và kiến trúc)
- 44 file `*IT.java` (integration, chạy với database thật)

Lệnh kiểm tra: `find backend/src/test -name '*Test.java' | wc -l` và `find backend/src/test -name '*IT.java' | wc -l`.

Một số file đáng biết:

| File | Kiểm tra |
|---|---|
| `ArchitectureTest.java` | Quy tắc phụ thuộc giữa tầng và module (xem [MODULES.md](MODULES.md)) |
| `AuthorizationCoverageTest.java` | Mọi controller có `@PreAuthorize` |
| `com/rentgg/docs/` (`ApiDocsDisabledTest`, `ProdApiDocsDisabledIT`, `StagingApiDocsDisabledIT`, `ApiDocsEnabledInTestProfileIT`) | OpenAPI tắt ở prod và staging, bật ở test |
| `shared/media/S3ObjectStorageTest.java` | Lưu media trên S3, dùng S3Proxy làm giả lập |
| `MediaObjectStorageIT.java` | Luồng media ở tầng ứng dụng |
| `PaymentProvidersIT.java`, `MomoWebhookIT.java`, `PayosWebhookIT.java` | Adapter PSP với webhook giả lập |
| `LoginBackoffIT.java` | Giới hạn đăng nhập sai |

## Chạy backend test

Test dùng database `rentgg_test`. Test tự xóa và migrate database này, không bao giờ trỏ vào database dev.

```bash
cd backend
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test \
TEST_DB_USERNAME=rentgg \
TEST_DB_PASSWORD=<mật khẩu của user test> \
./mvnw -B test
```

Yêu cầu: PostgreSQL 16 chạy ở cổng 5432, có user và database tương ứng. Trên Windows dev, `CLAUDE.md` mô tả cách dùng PostgreSQL portable. Trong CI, `backend.yml` dựng PostgreSQL 16 làm service.

Nếu không đặt `TEST_DB_URL`, `support/IntegrationTest.java` dùng Testcontainers để dựng PostgreSQL 16 tự động. Cách này cần Docker daemon đang chạy. Các test cần quyền tạo role (`DbRolesIT`) tạo database tạm `rentgg_roles_test` khi có `TEST_DB_URL`, và tự bỏ qua khi không kết nối được.

Mật khẩu test không ghi trong tài liệu. Đặt biến khi chạy; không commit.

Chạy một test:

```bash
./mvnw -B test -Dtest=ArchitectureTest
```

## CI backend

`.github/workflows/backend.yml`, chạy khi có thay đổi trong `backend/**` hoặc `deploy/db/**`:

1. Dựng PostgreSQL 16 service.
2. `./mvnw -B test`.
3. `docker build` image backend (không chạy image).

## CI web

`.github/workflows/web.yml`, Node 22:

1. `npm ci`
2. `npm run typecheck`
3. `npm run build`
4. Kiểm tra Caddyfile bằng `caddy:2.10-alpine caddy validate`.

Web không có test đơn vị tự động (UNKNOWN: chưa tìm thấy script test trong `frontend/package.json`).

## CI iOS

`.github/workflows/ios.yml`, macOS:

1. `xcodebuild -version`
2. `swift test --disable-swift-testing` (package RentGGCore)
3. `xcodegen generate`
4. Build cho simulator.

## E2E trình duyệt

- Script có sẵn trong repo: `scripts/e2e/staff-mfa.js` (Playwright). Không được gọi từ workflow nào.
- Bộ E2E cho luồng web (`frontend/e2e/web-evidence.cjs`) nằm trên nhánh `claude/e2e-web-evidence`, chưa vào `dev`. Trạng thái PR: xem PR #30 trên GitHub.
- Bằng chứng ảnh và video của các lần chạy nằm trên nhánh `claude/e2e-evidence-store`, thư mục `e2e-evidence/`.
- Trạng thái: PARTIAL. Chạy được trên môi trường có backend và web; chưa được đưa vào CI.

## Quy ước khi thêm test

- Test database đi qua Flyway, không tạo bảng bằng tay.
- Thời gian trong test dùng `MutableClock` (không dùng thời gian thật).
- Test không chứa giá trị thật: dùng giá trị mock.
- Thêm endpoint mới: thêm kiểm tra phân quyền (xem `AuthorizationCoverageTest`).
- Thêm module mới: đăng ký trong `ArchitectureTest` nếu cần.

## Giới hạn đã biết

- Không có test cho Android.
- Chưa có test trên thiết bị hoặc simulator thật cho luồng mobile.
- Luồng thanh toán với sandbox PSP thật: chưa có bằng chứng (xem [DECISIONS.md](DECISIONS.md)).
- Số test pass hiện tại: chưa đo lại trong tài liệu này. Chạy `./mvnw -B test` để có số.
