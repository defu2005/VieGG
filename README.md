# RentGG

Marketplace cho thuê account game. Monorepo gồm backend (Spring Boot), web (React), ứng dụng Android và iOS, cùng tài liệu và cấu hình triển khai.

Trạng thái hiện tại theo từng phần: xem [docs/RentGG_Implementation_Status.md](docs/RentGG_Implementation_Status.md) và [docs/MODULES.md](docs/MODULES.md). Tài liệu kỹ thuật này ghi theo code, ngày 2026-11-21.

## Cấu trúc thư mục

```text
backend/        Spring Boot 3.3.5, Java 21, Maven wrapper, Dockerfile
frontend/       Web React 18.3 + Vite 5.4
android/        Ứng dụng Android (Kotlin, Compose)
ios/            Ứng dụng iOS (SwiftUI, package RentGGCore)
deploy/         Triển khai production: Docker Compose, Caddy, sao lưu
docs/           Tài liệu thiết kế và tài liệu kỹ thuật
scripts/        Script hỗ trợ (db.cmd cho Windows, e2e)
docker-compose.yml   Môi trường dev: PostgreSQL + backend
```

Module backend và phụ thuộc giữa chúng: [docs/MODULES.md](docs/MODULES.md).

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Kiến trúc, bảo mật, outbox, job, thanh toán, profile |
| [MODULES.md](docs/MODULES.md) | Danh sách module backend, tầng, phụ thuộc |
| [DATABASE.md](docs/DATABASE.md) | Schema, bảng, bất biến ở tầng database, migration |
| [API.md](docs/API.md) | Quy ước API, endpoint công khai, lỗi, idempotency |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Cách chạy backend, web, cổng, biến môi trường, tua thời gian |
| [TESTING.md](docs/TESTING.md) | Lệnh kiểm thử, CI, E2E, giới hạn đã biết |
| [DECISIONS.md](docs/DECISIONS.md) | Quyết định kỹ thuật và các điểm còn mở |
| [RentGG_Production_Deploy.md](docs/RentGG_Production_Deploy.md) | Triển khai production |
| [RentGG_System_Design_v1.2.md](docs/RentGG_System_Design_v1.2.md) | Thiết kế hệ thống (tài liệu gốc) |
| [RentGG_Use_Cases_China_Model_v8.md](docs/RentGG_Use_Cases_China_Model_v8.md) | Use case (tài liệu gốc) |

## Chạy nhanh

Chi tiết và biến môi trường: [DEVELOPMENT.md](docs/DEVELOPMENT.md).

Backend với profile `dev` (cần PostgreSQL 16 ở cổng 5432, database `rentgg`):

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Kiểm tra: `http://localhost:8080/actuator/health` trả `UP`.

Web:

```bash
cd frontend
npm ci
npm run dev       # http://localhost:5173, proxy /api tới backend
```

Docker Compose (có `.env` copy từ `.env.example`):

```bash
docker compose up -d db   # Máy Windows dev theo CLAUDE.md: không dùng container này
```

Tài khoản mẫu và luồng thử tay ở profile `dev`: xem [DEVELOPMENT.md](docs/DEVELOPMENT.md#luồng-thử-tay-profile-dev).

## Kiểm thử

```bash
cd backend
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test \
TEST_DB_USERNAME=rentgg \
TEST_DB_PASSWORD=<mật khẩu user test> \
./mvnw -B test
```

Không có `TEST_DB_URL` thì test dùng Testcontainers (cần Docker). Chi tiết, CI và giới hạn: [TESTING.md](docs/TESTING.md).

## Migration

37 file Flyway, `backend/src/main/resources/db/migration/V1__…` đến `V37__…`. Quy tắc đánh số và danh sách bảng: [DATABASE.md](docs/DATABASE.md).

## Triển khai

Production dùng `deploy/docker-compose.prod.yml` (db, app, caddy, backup). Xem [RentGG_Production_Deploy.md](docs/RentGG_Production_Deploy.md). Không chạy workflow deploy khi chưa có quyết định của người quản lý dự án.

## Quy tắc làm việc

Quy tắc cho người và agent nằm trong [CLAUDE.md](CLAUDE.md) và [AGENTS.md](AGENTS.md). Khi đổi module, endpoint, schema, hoặc lệnh chạy, cập nhật tài liệu kỹ thuật tương ứng trong `docs/` (mục "Duy trì tài liệu" trong hai file trên).
