# Phát triển

Ngày kiểm tra **2026-11-21**. Mục này chỉ mô tả cách chạy. Quy tắc làm việc cho agent nằm trong `CLAUDE.md` và `AGENTS.md`. Kiểm thử xem [TESTING.md](TESTING.md).

## Yêu cầu

| Công cụ | Dùng cho | Ghi chú |
|---|---|---|
| JDK 21 | Backend | Maven wrapper có sẵn (`backend/mvnw`) |
| Node.js 22 | Web | CI dùng Node 22 (`.github/workflows/web.yml`) |
| PostgreSQL 16 | Backend | Cổng 5432. Cách có sẵn: `docker compose up db` hoặc PostgreSQL cài trực tiếp |
| Docker | Tùy chọn | Dùng cho `docker-compose.yml` và build image |
| Xcode, Android Studio | iOS, Android | Không cần cho backend và web |

## Cổng và địa chỉ

| Thành phần | Cổng | Ghi chú |
|---|---|---|
| PostgreSQL | 5432 | Tài khoản dev: `rentgg` |
| Backend | 8080 | Profile `dev` |
| Web (Vite) | 5173 | Cổng trong `frontend/vite.config.ts` |
| Web (Vite) trên máy Windows dev | 5273 | Theo `CLAUDE.md`. Cấu hình preview nằm ở `.claude/launch.json` |

Web gọi API qua đường dẫn tương đối `/api/v1`. Vite proxy `/api` tới `VITE_API_TARGET`, mặc định `http://localhost:8080`.

Mobile:

- Android emulator gọi backend ở `http://10.0.2.2:8080/api/v1/` (`android/app/build.gradle.kts`, build `debug`).
- iOS simulator gọi `http://localhost:8080/api/v1/` (`ios/project.yml`, `APIBaseURL`).
- Thiết bị thật: dùng IP LAN của máy chạy backend, cổng 8080.

## Chạy backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Kiểm tra: `curl http://localhost:8080/actuator/health` trả `UP`.

Đổi cổng: đặt `SERVER_PORT` và `RENTGG_PUBLIC_BASE_URL` cho khớp (cơ chế biến môi trường của Spring; chưa kiểm chứng trên máy này).

Chạy với database khác: đặt `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` (tên biến lấy theo `application.yml`). Đừng dùng database `rentgg_test` cho việc chạy thử.

## Chạy web

```bash
cd frontend
npm ci
npm run dev
```

Các script khác trong `frontend/package.json`:

| Lệnh | Việc làm |
|---|---|
| `npm run typecheck` | `tsc --noEmit` |
| `npm run build` | `tsc -b && vite build` |
| `npm run preview` | Xem bản build |

## Chạy bằng Docker Compose

`docker-compose.yml` ở gốc có hai service: `db` (postgres:16, cổng 5432) và `app` (build `./backend`, `127.0.0.1:8080:8080`).

```bash
cp .env.example .env     # điền giá trị dev; không commit file .env
docker compose up -d db
```

Không chạy `docker compose up db` cùng lúc với PostgreSQL cài trực tiếp: cả hai đều chiếm cổng 5432. Trên máy Windows dev theo `CLAUDE.md`, không dùng container `db`; dùng PostgreSQL portable.

Triển khai production dùng `deploy/docker-compose.prod.yml`. Xem [RentGG_Production_Deploy.md](RentGG_Production_Deploy.md).

## Biến môi trường

Chỉ liệt kê tên. Giá trị thật không được ghi vào tài liệu hay file được commit.

| Biến | Nơi khai báo | Dùng cho |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `.env.example` | Chọn profile (`dev`, `staging`, `prod`) |
| `DB_PASSWORD` | `.env.example` | Mật khẩu database |
| `RENTGG_JWT_SECRET` | `.env.example`, `deploy/.env.prod.example` | Ký JWT |
| `RENTGG_CREDENTIAL_KEYS`, `RENTGG_CREDENTIAL_ACTIVE_KEY_ID` | `.env.example`, `deploy/.env.prod.example` | Khóa mã hóa credential account game |
| `RENTGG_PAYMENT_WEBHOOK_SECRET` | `.env.example`, `deploy/.env.prod.example` | Xác thực webhook |
| `RENTGG_PAYMENT_PROVIDER`, `RENTGG_PAYMENT_FAKE_ENABLED` | `deploy/.env.prod.example` | Chọn PSP và bật gateway giả |
| `RENTGG_MEDIA_STORAGE` | `deploy/.env.prod.example` | `s3` hoặc lưu database |
| `RENTGG_CORS_ALLOWED_ORIGINS`, `RENTGG_PUBLIC_BASE_URL`, `RENTGG_FRONTEND_BASE_URL` | `deploy/.env.prod.example` | URL công khai |

Cấu hình đầy đủ và giá trị mặc định dev nằm ở `backend/src/main/resources/application.yml`. Giá trị mặc định dev trong file này chỉ dành cho máy phát triển; `StartupSafetyCheck` kiểm tra một số giá trị này khi khởi động trên staging và prod (xem [ARCHITECTURE.md](ARCHITECTURE.md#thanh-toán)).

## Tài khoản mẫu

Profile `dev` seed dữ liệu mẫu (`backend/src/main/java/com/rentgg/jobs/DevDataSeeder.java`). Email có đuôi `@rentgg.dev`, ví dụ `admin@`, `owner@`, `renter@`. Mật khẩu là hằng `DevDataSeeder.DEV_PASSWORD`; tài liệu này không ghi giá trị.

## Luồng thử tay (profile dev)

1. Đăng nhập bằng `renter@rentgg.dev` (mật khẩu theo `DevDataSeeder.DEV_PASSWORD`).
2. Tìm listing: `GET /api/v1/listings/search`.
3. Tạo đơn: `POST /api/v1/orders`, bắt buộc header `Idempotency-Key`.
4. Thanh toán: `POST /api/v1/orders/{id}/pay`. Phản hồi có `checkoutUrl` và `intentId`.
5. Giả lập thanh toán thành công: `POST /api/v1/dev/payments/{intentId}/simulate-success` (chỉ `dev` và `test`).
6. Chạy mọi job ngay: `POST /api/v1/dev/jobs/run-all`, cần quyền `dev:run_jobs` (`@PreAuthorize` trong `jobs/DevController.java`).

Đơn có thể ở trạng thái `AWAITING_OWNER_APPROVAL`, chờ chủ đơn duyệt, tùy cấu hình listing.

## Tua thời gian

Đồng hồ nghiệp vụ có thể tua khi chạy profile `dev`. Dùng để kiểm thử các luồng theo thời gian như hết hạn thanh toán, bắt đầu và kết thúc thuê.

- Web: nút đồng hồ ở góc dưới trái, chỉ hiện ở bản dev. Component `frontend/src/components/DevTimePanel.tsx`.
- API (cần đăng nhập): `GET /api/v1/dev/time`, `POST /api/v1/dev/time/advance`, `POST /api/v1/dev/time/set`, `POST /api/v1/dev/time/freeze`, `POST /api/v1/dev/time/reset`.
- Độ lệch lưu ở `backend/target/dev-clock-<port>.properties`. Xóa file để về giờ thật.
- Các backend trên cổng khác nhau dùng chung database. Tua thời gian ở một backend thay đổi dữ liệu mà các backend khác cũng thấy.

Code: `backend/src/main/java/com/rentgg/jobs/devtime/`.

## Quy tắc khi sửa code

- Đổi module, tầng, hoặc phụ thuộc: xem [MODULES.md](MODULES.md). `ArchitectureTest` sẽ báo lỗi nếu vi phạm.
- Đổi schema: xem [DATABASE.md](DATABASE.md#migration). Không sửa migration đã áp dụng.
- Đổi endpoint: xem [API.md](API.md).
- Thời gian nghiệp vụ đọc qua bean `Clock`, không dùng `Instant.now()`.

## Cập nhật tài liệu này

Khi đổi lệnh, cổng, hoặc biến môi trường, cập nhật bảng tương ứng ở đây và ở [README.md](../README.md).
