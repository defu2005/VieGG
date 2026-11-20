# Brief cho agent cloud — RentGG

Repo: `defu2005/VieGG`. Làm việc trên nhánh `dev`. Đây là một session tuần tự: làm lần lượt từng mục theo hàng đợi bên dưới.

## Bước đầu (bắt buộc)
1. `git fetch origin dev && git switch dev`
2. Đọc `CLAUDE.md` mục "Môi trường cloud (Linux, agent từ xa)", `docs/RentGG_Dev_Guide.md` (§0, Phụ lục A), và file này.
3. Mỗi mục làm trên một nhánh con riêng tách từ `dev`, mở PR vào `dev`. Nếu PR trước chưa merge, tách từ nhánh của PR đó và ghi rõ trong mô tả PR.

## Quy tắc cứng
- Không push `main`. Không chạy `deploy.yml`.
- Không sửa migration V1–V30 (`backend/src/main/resources/db/migration`). Migration mới đánh số từ V31. Session này là người duy nhất tạo migration.
- Test dùng giá trị mock. Không đưa secret, key PSP, khóa S3/R2, keystore thật vào file được commit.
- Không claim "xanh" nếu chưa chạy thật. Không bỏ qua hook hay test đỏ để merge.
- Cloud không có Docker daemon. Mục nào cần Docker thì dùng binary độc lập hoặc store trong bộ nhớ, và ghi rõ trong guide là chưa chạy phần Docker.

## Hàng đợi (làm theo thứ tự)

| Bước | Mục guide | Việc | Xong khi |
|---|---|---|---|
| 0a | §1.1 | Dựng PostgreSQL 16 như service trong `backend.yml` (user `rentgg`/`rentgg_dev`, cổng 5432, DB `rentgg_test`). Chạy `cd backend && ./mvnw -B test` với `TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev`. | Ghi số test chạy/lỗi/bỏ qua thật vào §1.1. Test đỏ có sẵn thì ghi nguyên nhân. |
| 0b | §1.2 | Chẩn đoán CI Backend đỏ ở run `44692352655` (bước `Test`). Log GitHub cần đăng nhập, nên tái hiện bằng cách chạy test ở 0a. Ghi kết quả vào §1.2. | Biết nguyên nhân. Sửa nếu do cấu hình CI; nếu do test thật thì ghi là baseline failure. |
| 1 | §2.0 | `StartupSafetyCheck` (`backend/src/main/java/com/rentgg/config/`) phải từ chối `RENTGG_PAYMENT_PROVIDER=FAKE` trên staging/prod, và từ chối key/secret còn giá trị placeholder/dev (xem Phụ lục A và `.env` mẫu). Viết unit test cho từng nhánh. | Test pass; mục 2.0 được tick kèm kết quả. |
| 2 | §2.1 | Lọc dữ liệu nhạy cảm trong log (§16.1). | Test cho thấy log không lộ dữ liệu nhạy cảm. |
| 3 | §2.2 | Tách user DB `rentgg_app` và `rentgg_migrator` (§5.4, §14.3). Migration mới nếu cần, số từ V31. | Test/CI xác nhận app chạy bằng user không có quyền DDL. |
| 4 | §2.3 | Tắt Swagger/API docs trên staging/prod (§11.1). | Test xác nhận endpoint docs không mở trên staging/prod. |
| 5 | §2.4 | TOTP RFC 6238 bắt buộc cho SUPPORT, ADMIN, FINANCE (§4.2). | Staff không có TOTP bị chặn; test mã đúng, sai, đã dùng. |
| 6 | §4.1 | Contract test với mock HTTP server (WireMock hoặc MockWebServer) cho từng adapter trong `backend/src/main/java/com/rentgg/payment/infrastructure/psp/`. **PayOS trước**, sau đó Vnpay, Momo, Zalopay. Chỉ làm checklist `[M]`, bỏ qua `[S]`. | Mỗi adapter có test cho: tạo thanh toán, webhook hợp lệ, webhook sai chữ ký, webhook phát lại, hoàn tiền toàn phần/một phần, timeout/5xx. |
| 7 | §4.2 | Test `S3ObjectStorage` với MinIO (binary độc lập, không Docker) hoặc server S3-tương thích; test backfill từ `db` sang storage không mất dữ liệu. | Test backfill pass. Ghi rõ phần MinIO nếu không chạy được. `[S]` R2 để trống. |
| 8 | §4.3 | Kiểm tra lại: không có adapter email/push (xem guide). | Ghi kết quả kiểm tra; không tạo adapter mới. |
| 9 | §3, mục 5 | Re-auth khi xem credentials (`rentgg.credentials.require-reauth`). | Cờ có tác dụng, có test. |
| 10 | §3, mục 6 | Rate limit đăng nhập lũy tiến (§12.5). | Thời gian chờ tăng sau nhiều lần sai, có test. |
| 11 | §3, mục 7 | Ghi `abuse_signals` khi vượt giới hạn xem credentials. | Có test. |
| 12 | §3, mục 8 | Ba metric xuất hiện trên `/actuator/prometheus` (§16.2). | Test kiểm tra endpoint. |
| 13 | §3, mục 9 | MDC `userId` và `orderId` trong log. | Log có hai trường. |
| 14 | §3, mục 10 | Rule alert cho outbox kẹt và refund lỗi (file cấu hình trong `jobs/`). | File rule có mặt và được kiểm tra cú pháp. |
| 15 | §3, mục 11 | Endpoint quản trị: danh sách chờ `verify-recovery`, danh sách user, tra audit. | Có test. |
| 16 | §3, mục 12 | Kháng nghị quyết định (`dispute/`), một lần cho mỗi chủ account. | Có test. |
| 17 | §3, mục 13 | Hồ sơ thông báo pháp lý (UC-20): bảng và endpoint tiếp nhận, có hạn phản hồi. Migration từ V31 nếu cần. | Có test. |
| 18 | §3, mục 14 | Job đánh dấu quá hạn phân xử (`deadline_at`). | Có test. |
| 19 | §3, mục 15 | Giới hạn body JSON 1 MB (§12.10). Body lớn hơn bị 413. | Có test. |
| 20 | §3, mục 16 | Template thông báo theo sự kiện (`notification/`). | Mỗi sự kiện có tiêu đề và nội dung riêng; có test. |
| 21 | §3, mục 17 | Kiểm tra CI backend và web (xem §1.2). | Trạng thái CI được ghi lại. |
| 22 | §6.1 | Sửa lỗi build iOS trên `ios.yml` (macOS-15). Đọc log nếu có quyền; nếu không đọc được thì ghi rõ. | `ios.yml` xanh, hoặc ghi rõ không xác minh được. |
| 23 | §6.2 | Ký release Android từ biến môi trường hoặc `~/.gradle/gradle.properties` ngoài repo. Không đưa khóa vào repo. | `./gradlew assembleRelease` tạo APK ký khi có biến; thiếu biến thì rơi về unsigned. |
| 24 | §7 | Cập nhật `RentGG_Implementation_Status.md`, `RentGG_Frontend_Status.md`, `RentGG_iOS_Status.md`: 30 migration, CI đã có, tình trạng Docker/ảo hóa Android, trạng thái §4.1 sau khi làm. | Đối chiếu với `git log`; mỗi file có ngày cập nhật. |

## Không làm trong cloud (bỏ qua, ghi lý do trong guide)
- **§1.3**: tua thời gian trên trình duyệt. Cần máy cục bộ.
- **§5**: dry-run production. Cần Docker daemon.
- **§6.3**: E2E trên emulator hoặc điện thoại. Cần máy cục bộ.
- Mọi checkbox `[S]`: cần khóa sandbox thật, sẽ điền sau.

## Khi bị chặn
- Ghi lý do vào guide ở mục đó, rồi chuyển sang mục kế tiếp. Không dừng cả hàng đợi.
- Mỗi mục xong: tick trong guide, ghi kết quả test thật, mở PR vào `dev`.
