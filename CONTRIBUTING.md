# Đóng góp vào RentGG

Hướng dẫn này lấy từ `CLAUDE.md` (quy tắc cho agent và người làm việc cùng), `AGENTS.md`, và các workflow trong `.github/workflows/`.

## Nhánh và pull request

- Làm việc trên nhánh con tách từ `dev`. Mở PR vào `dev`.
- Không push `main`. Không chạy `.github/workflows/deploy.yml` nếu không được chủ dự án yêu cầu.
- Test phải xanh trước khi mở PR. Nếu test đỏ sẵn từ baseline, ghi rõ trong PR, không che lỗi.

## Migration (Flyway)

- Thư mục: `backend/src/main/resources/db/migration/`.
- Không sửa migration đã có. `CLAUDE.md` và `AGENTS.md` ghi "không sửa V1–V30, mới đánh số từ V31". Repo đã có V1–V37, nên migration kế tiếp là **V38**. Hai file đó còn ghi quy tắc cũ; chủ dự án cần cập nhật.
- Mỗi thời điểm chỉ một người hoặc một agent được tạo migration mới.

## Chạy kiểm thử

Backend (cần PostgreSQL 16 với user `rentgg`, mật khẩu `rentgg_dev`, cổng 5432, database `rentgg_test`):

```bash
cd backend
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test \
TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev \
./mvnw -B test
```

Test sẽ clean và migrate `rentgg_test`. Không trỏ `TEST_DB_URL` vào database `rentgg`.

Web:

```bash
cd frontend
npm ci
npm run typecheck
npm run build
```

iOS: `ios/project.yml` dùng XcodeGen. Workflow `.github/workflows/ios.yml` chạy trên macOS.

## CI

| Workflow | Chạy khi | Làm gì |
|---|---|---|
| `backend.yml` | Thay đổi trong `backend/**`, `deploy/db/**` | Test Java 21 với PostgreSQL, build Docker image |
| `web.yml` | Thay đổi trong `frontend/**`, `deploy/web.Dockerfile`, `deploy/Caddyfile` | `npm ci`, typecheck, build, kiểm tra cấu hình Caddy |
| `ios.yml` | Thay đổi trong `ios/**` | `swift test`, XcodeGen |
| `deploy.yml` | Chạy tay | Triển khai production. Không chạy khi chưa được phê duyệt |

Không có workflow cho `android/`.

## Thay đổi về bảo mật và tiền

- Không thêm bí mật thật vào repo. Xem `SECURITY.md`.
- Thay đổi về xác thực hai bước (MFA) cho nhân viên cần chủ dự án phê duyệt rõ ràng.
- Thay đổi về ledger, phê duyệt kép, hoặc hoàn tiền cần test bao phủ luồng đó.

## Tài liệu

- Mục lục: `docs/README.md`.
- Khi tài liệu khác mã, sửa tài liệu theo mã, trừ khi thay đổi mã là chủ đích và đã được ghi nhận.
- Không tạo PR nếu chưa được yêu cầu.
