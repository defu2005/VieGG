# RentGG — hướng dẫn cho Claude

## Môi trường dev chuẩn (BẮT BUỘC dùng cho mọi phiên)

Mọi phiên làm việc — backend, frontend, Android, iOS — dùng chung đúng bộ dưới đây. Không tự dựng database mới, không đổi cổng chuẩn.

| Thành phần | Cấu hình chuẩn |
| --- | --- |
| Database | PostgreSQL 16 **portable** tại `%LOCALAPPDATA%\rentgg-tools` (`pg16\pgsql\bin`, dữ liệu ở `pgdata`), cổng **5432** |
| Tên DB | `rentgg` (dev chính) · `rentgg_test` (test tự động) · `rentgg_media_ui` (chỉ dùng cho cặp cấu hình `*-media`) |
| User DB | `rentgg` / `rentgg_dev` |
| Backend | Spring Boot, profile `dev`, cổng **8080** |
| Frontend web | Vite, cổng **5273**, `VITE_API_TARGET=http://localhost:8080` |
| Android (emulator) | `http://10.0.2.2:8080/api/v1/` (build `debug`, `android/app/build.gradle.kts`) |
| iOS (simulator) | `http://localhost:8080/api/v1/` (Debug, `ios/project.yml` → `APIBaseURL`) |
| Điện thoại thật | IP LAN của máy Windows, cổng 8080 |
| Tài khoản mẫu | `admin@`, `owner@`, `renter@`… đuôi `@rentgg.dev`, mật khẩu `DevDataSeeder.DEV_PASSWORD` |

### Khởi động (theo thứ tự)
1. DB: `preview_start` với `rentgg-db` (hoặc tự chạy `scripts\db.cmd start`). Nếu cổng 5432 đã có `postgres.exe` của `rentgg-tools` đang chạy thì dùng luôn.
2. Backend: `preview_start` với `rentgg-backend` → kiểm tra `http://localhost:8080/actuator/health` = `UP`.
3. Frontend: `preview_start` với `rentgg-frontend` → `http://localhost:5273`.

Cấu hình nằm trong `.claude/launch.json`. Các cặp `*-alt`, `*-ui`, `*-8081`, `*-media` chỉ dùng khi cần chạy song song nhiều phiên; chúng vẫn dùng chung PostgreSQL portable ở trên.

### Không dùng
- Container Docker `rentgg-db-1` (`docker compose up db`): DB trống, chỉ có dữ liệu mẫu. Nó cũng chiếm cổng 5432 nên phải tắt (`docker compose stop db`) trước khi bật DB portable.
- PostgreSQL trong WSL Ubuntu: DB cũ, dừng ở migration 7.

### Tua thời gian (chỉ profile `dev`)
Đồng hồ nghiệp vụ của backend (`Clock`) có thể tua để test các luồng theo thời gian (hết hạn thanh toán, bắt đầu/kết thúc thuê, nhắc giờ, giải ngân, hoàn cọc…).
- Web: nút 🕒 góc dưới trái (chỉ ở bản dev) — tua nhanh, đặt giờ, nhảy tới "mốc sắp tới", đóng băng, về giờ thật.
- API (cần đăng nhập): `GET /api/v1/dev/time`, `GET /dev/time/upcoming?mine=true`, `POST /dev/time/advance {"duration":"PT2H"}`, `POST /dev/time/set {"at":"..."}`, `POST /dev/time/freeze {"frozen":true}`, `POST /dev/time/reset`. Mặc định dừng ở từng mốc trên đường và chạy job (`stepThroughDeadlines`, `runJobs`); mỗi lần tua trả token mới.
- Độ lệch lưu ở `backend/target/dev-clock-<port>.properties` (giữ qua khởi động lại; xoá file = về giờ thật). Mỗi cổng backend có đồng hồ riêng nhưng dùng chung DB, nên tua ở một backend sẽ làm thay đổi dữ liệu mà các phiên khác cũng thấy.
- Code: `backend/.../jobs/devtime/`, `frontend/src/components/DevTimePanel.tsx`, `frontend/src/lib/serverClock.ts` (web dùng `nowMs()` thay cho `Date.now()`).

### Test backend
`TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev ./mvnw test` (test sẽ clean + migrate `rentgg_test`, không bao giờ trỏ vào `rentgg`).

Tài liệu cho người: "Hướng dẫn chạy RentGG" — https://claude.ai/artifact/LausFpCg2umgshRuBdRh9U
