# RentGG — Hướng dẫn thực hiện các việc dev

**Ngày:** 2026-11-20
**Đối chiếu với:** `docs/RentGG_Implementation_Status.md` (§4.1), `docs/RentGG_Production_Deploy.md`, `docs/RentGG_Validation_Checklist.md`
**Phạm vi:** chỉ việc code và kiểm thử. Việc ngoài code (pháp lý, tài khoản merchant, tên miền, VPS, R2, Apple/Google) do bạn làm sau; các giá trị cần điền khi đó liệt kê ở **Phụ lục A**.

---

## Thứ tự cho agent cloud

Agent cloud làm theo thứ tự này: **2.0 → 2.1 → 2.2 → 2.3 → 4.1 → 4.2 → mục 3**.
Các mục **chỉ làm trên máy cục bộ**, agent cloud bỏ qua: **1.3** (tua thời gian trên trình duyệt), **5** (dry-run có Docker daemon), **6.3** (E2E emulator/điện thoại).
Mục 1.1 và 1.2 là baseline: agent chạy trước khi bắt đầu để biết lỗi nào đã có sẵn.

## 0. Nguyên tắc chung

1. **Test dùng mock, không gọi dịch vụ thật.** Mỗi tích hợp bên ngoài (PSP, S3/R2, email…) có hai mức kiểm tra:
   - `[M]` **Đạt với mock**: có thể làm ngay, không cần khóa thật.
   - `[S]` **Đạt với sandbox thật**: để trống đến khi bạn có khóa sandbox. Không được đánh dấu xong nếu chưa chạy `[S]`.
2. **Mock không được lọt lên production.** Mục 2.0 bên dưới chặn ứng dụng khởi động trên staging/prod nếu còn provider giả lập hoặc giá trị placeholder.
3. **Giá trị thật chỉ nằm trong file gitignore.** Quy tắc này áp dụng cho:
   - `.env` (dev, đã có trong `.gitignore` dòng 2 và 12–13);
   - `deploy/.env.prod` (đã có trong `.gitignore` dòng 24).

   Không bao giờ điền khóa thật vào `.env.example`, `deploy/.env.prod.example` hay bất kỳ file nào được commit. Khi commit, kiểm tra `git diff --cached` trước.
4. **Thời gian chỉ đi qua `Clock`.** Test dùng `MutableClock`; kiểm tra trên web bằng 🕒 (chỉ profile `dev`).
5. **Môi trường chuẩn** (theo `CLAUDE.md` gốc): PostgreSQL 16 portable ở cổng 5432, DB `rentgg` cho dev, `rentgg_test` cho test tự động; backend 8080; web 5273.
6. **Ghi số liệu thật.** Mọi con số về số test phải lấy từ lần chạy mới, không chép từ tài liệu cũ.

---

> **Lưu ý:** các cột "Khu vực" và tiêu chí xong trong mục 2–3 là gợi ý, chưa đối chiếu hết với code. Agent phải kiểm tra bằng grep trước khi làm.

## 1. Xác minh nền tảng

### 1.1 Chạy toàn bộ test backend trên `rentgg_test`
- **Làm gì:** `TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test TEST_DB_USERNAME=rentgg TEST_DB_PASSWORD=rentgg_dev ./mvnw test`
- **Khu vực:** `backend/src/test/`
- **Xong khi:** ghi lại số test chạy, số lỗi, số bỏ qua; mọi test đỏ có ghi chú nguyên nhân.
- **Kết quả thực tế (2026-11-20, PostgreSQL 16.15 cục bộ, `rentgg_test`):**
  - Baseline trước mọi thay đổi (commit `dev` 5828bd2): **116 test, 0 lỗi, 0 fail, 0 skip**, BUILD SUCCESS.
  - Sau §2.0 (commit `e65c1d4`): **184 test, 0 fail, 1 lỗi**. Lỗi duy nhất là `MoneyAndApprovalIT.concurrentBooking_exactlyOneWins` (xem 1.2). Lượng test tăng 68 từ `StartupSafetyCheckTest`.
  - Chạy lại riêng `MoneyAndApprovalIT` 3 lần trên cùng commit: 2 pass, 1 lỗi.
- **Kiểm thử:** chính lệnh trên `[M]` (DB thật cục bộ nhưng không gọi dịch vụ ngoài).

### 1.2 CI xanh lần đầu
- **Làm gì:** kiểm tra tab Actions của `defu2005/VieGG` cho ba workflow `backend.yml`, `web.yml`, `ios.yml`. Sửa lỗi nếu có.
- **Lưu ý:** `backend.yml` có service Postgres 16 và đã chạy `./mvnw -B test` trên `ubuntu-24.04`. `ios.yml` chạy trên `macos-15`.
- **Xong khi:** cả ba workflow xanh trên commit `2f69c2c` hoặc commit mới hơn.
- **Kiểm thử:** xem log CI.
- **Phân loại lỗi CI (run 44692352655, commit `5e91584`):** không phải lỗi cấu hình CI. Lỗi là `MoneyAndApprovalIT.concurrentBooking_exactlyOneWins:77` với `40P01 deadlock detected` trên ràng buộc loại trừ `ex_orders_no_overlap` khi 10 giao dịch đặt cùng một tài khoản game cùng lúc. Postgres kill một giao dịch thay vì trả `SLOT_UNAVAILABLE`, nên lỗi flaky. Lịch sử theo commit (cập nhật khi có kết quả mới):
  - `44692352655` (`5e91584`, CI): **đỏ**, deadlock `concurrentBooking_exactlyOneWins`.
  - `44705981405` (`e65c1d4`, CI push): **đỏ** vì không tải được Maven wrapper (`wget` tới repo.maven.apache.org). Lỗi hạ tầng, không phải test; chưa sửa.
  - `44706483775` (`b24a31c`, CI PR): **xanh**, 184 test, 0 fail, 0 lỗi.
  - `44708319565` (`6e01dd6`, CI PR): **đỏ**, 184 test, 1 lỗi; log chỉ có `concurrentBooking_exactlyOneWins`.
  - `44708631585` (`f009a08`, CI PR): **xanh**.
  - Local, cùng commit đầu: 2 fail trên 5 lần chạy `MoneyAndApprovalIT` (gồm 1 baseline pass, 1 full suite fail, 3 lần chạy lại: 2 pass, 1 fail).
- **Kết quả cũ (2026-11-20, commit `5e91584`):** `Web` xanh (run 44692352516). `Backend` đỏ ở bước `Test` (run 44692352655, exit 1); log yêu cầu đăng nhập GitHub nên chưa đọc được nguyên nhân. Chưa sửa. Cần agent cloud đọc log, phân loại lỗi, và ghi vào đây là baseline failure hoặc sửa nếu do cấu hình CI. `ios.yml` chưa kiểm tra trên `dev`.

### 1.3 Đi qua các luồng web chưa thử bằng tua thời gian
Dùng 🕒 (profile `dev`) hoặc `POST /api/v1/dev/time/*`. Mỗi luồng là một checklist:

| Luồng | Điều kiện cần | Xong khi |
|---|---|---|
| Mở khiếu nại và thêm bằng chứng | Đơn `IN_RENTAL` đã xem credentials | Khiếu nại được tạo, bằng chứng https được lưu |
| Phân xử khiếu nại (SUPPORT) | Có khiếu nại đang mở | Khấu trừ cọc, hoàn tiền hoặc đổi trạng thái đúng sổ cái |
| OTP (game `OWNER_ASSISTED_OTP`) | Game bật OTP | Yêu cầu và gửi OTP chạy được, hết hạn đúng `otp-note-ttl-minutes` |
| Rút tiền và đánh dấu đã chi/thất bại (FINANCE) | Ví có số dư | Trạng thái rút tiền và sổ cái cân bằng |
| Lấy lại account (`reclaim`) | Account `IN_USE` quá hạn | Account về trạng thái đúng, listing cập nhật |
| Phê duyệt kép | Hai tài khoản staff khác nhau | Người thứ hai duyệt được, người tạo không tự duyệt được |
| KYC và trạng thái người dùng | ADMIN | Đổi trạng thái, mức rủi ro, duyệt KYC đều ghi audit |

- **Xong khi:** mỗi dòng có ảnh chụp hoặc mã đơn đã kiểm tra; lỗi phát hiện được tạo thành một task trong mục 2–3.
- **Kiểm thử:** thủ công trên 5273 + API. Với luồng quan trọng, thêm test tích hợp `[M]` nếu chưa có.

---

## 2. Bảo mật mức Cao

### 2.0 Chặn mock và placeholder khi chạy staging/prod (**chặn trước khi làm mục 4**)
- **Làm gì:** trong `StartupSafetyCheck` thêm kiểm tra cho profile `prod` và `staging`:
  - `RENTGG_PAYMENT_PROVIDER` không được là `FAKE`;
  - khóa của provider đang chọn không được rỗng hoặc chứa chuỗi placeholder (`changeme`, `ACCOUNT_ID`, `example`, `xxx`);
  - `RENTGG_S3_*` phải có đủ khi `RENTGG_MEDIA_STORAGE=s3`;
  - `RENTGG_CORS_ALLOWED_ORIGINS` và các URL công khai đã được kiểm tra HTTPS (đã có).
- **Hiện trạng:** đã chặn `RENTGG_PAYMENT_FAKE_ENABLED=true` trên prod, nhưng **chưa chặn** `RENTGG_PAYMENT_PROVIDER=FAKE` khi `fake-enabled=false`.
- **Xong khi:** unit test cho từng trường hợp, khởi động prod với giá trị sai phải thất bại có thông báo rõ.
- **Kiểm thử:** `[M]`.
- **Kết quả (2026-11-20, commit `b0ab6be`):** **ĐÃ LÀM.**
  - Bổ sung theo brief: từ chối `RENTGG_JWT_SECRET` bằng giá trị dev mặc định; từ chối `changeme`, `account_id`, `example` và chuỗi một ký tự lặp lại trong JWT, webhook secret và `RENTGG_CREDENTIAL_KEYS`. `xxx` không dùng cho secret vì base64 ngẫu nhiên chứa nó khoảng 1 trên 500, và credential key không đổi được sau khi đã mã hóa dữ liệu.
  - Full suite trên `b0ab6be`: **197 test, 0 fail, 0 lỗi, BUILD SUCCESS**.
  - Boot prod với JWT dev mặc định (PAYOS đủ khóa, `RENTGG_MEDIA_STORAGE=db`): bị từ chối, `RENTGG_JWT_SECRET must not be the dev default on staging/prod`.
  - Unit test: `StartupSafetyCheckTest`, 81 test pass (68 ban đầu + 13 bổ sung cho secret). Negative control trên code cũ: 56/68 test ban đầu fail, và 11/13 test bổ sung fail.
  - Khởi động prod thật, PostgreSQL 16 cục bộ, DB `rentgg` (giá trị mock):
    - `RENTGG_PAYMENT_PROVIDER=FAKE`, `RENTGG_MEDIA_STORAGE=db`, PAYOS đủ khóa: bị từ chối, `must not be FAKE on staging/prod (mock provider)`.
    - PAYOS đủ khóa, `RENTGG_MEDIA_STORAGE=s3`, endpoint `ACCOUNT_ID` từ file mẫu: bị từ chối, `RENTGG_S3_ENDPOINT still holds a placeholder value`.
    - PAYOS đủ khóa, `RENTGG_MEDIA_STORAGE=db`: khởi động thành công (`Started RentGgApplication` trong 6.9 s). Tiến trình sau đó được dừng thủ công.
  - Ghi chú: khi nhiều giá trị sai cùng lúc, bean `mediaStore` được tạo trước `StartupSafetyCheck` nên thông báo S3 của `S3ObjectStorage` có thể hiện trước. Ứng dụng vẫn không khởi động. Chưa đổi thứ tự bean trong PR này.
  - Lưu ý: `RENTGG_CORS_ALLOWED_ORIGINS` chỉ kiểm tra không rỗng, trong khi mục trên ghi "đã kiểm tra HTTPS". Chưa sửa.
  - `RENTGG_PAYMENT_FAKE_ENABLED` trên staging vẫn được phép `true`; để nguyên, chờ quyết định.

### 2.1 Lọc dữ liệu nhạy cảm trong log (§16.1)
- **Làm gì:** thêm bộ lọc che các khóa `password`, `token`, `secret`, `authorization`, `key`, `signature` trong log JSON và log text. Áp dụng cho cả nội dung message lẫn MDC.
- **Khu vực:** cấu hình logback và `shared/`.
- **Xong khi:** test ghi log với các khóa trên và kiểm tra output không chứa giá trị gốc.
- **Kiểm thử:** `[M]`.
- **Kết quả (2026-11-20, nhánh `claude/row-02-log-masking`): ĐÃ LÀM.**
  - Cơ chế: `SensitiveDataMasker` che giá trị của khóa nhạy cảm (password, token, secret, signature, authorization, *key*) dạng `key=v`, `key: v`, `"key":"v"`, `"key" : "v"`, dạng escape JSON, và dạng có nháy đơn/kép. Giá trị có nháy được che tới nháy đóng. Dạng `Bearer ...` được che toàn bộ.
  - Text (dev, test): converter cho `%m`/`%msg`/`%message` và `%wEx`, không che cả dòng. Lý do: che cả dòng làm tên logger như `TokenService` bị đọc thành khóa (đã chứng minh: "Started fine" thành "**** fine").
  - JSON (prod): `MaskingJsonGeneratorDecorator` với `SensitiveJsonValueMasker`, che mọi giá trị chuỗi, kể cả `stack_trace` và MDC trong whitelist.
  - `DevDataSeeder` không còn in mật khẩu dev vào log.
  - Test: `SensitiveDataMaskerTest` 19 test; `LogbackConfigTest` 5 test chạy qua `logback-spring.xml` thật (profile `test` và `prod`). Negative control: trên config cũ, 4/5 test cấu hình fail (test probe tên logger pass cả hai, đúng như kỳ vọng).
  - Full suite: 221 test, 0 fail, 0 lỗi (run này `concurrentBooking_exactlyOneWins` pass).
  - Boot prod với JSON logging: `Started RentGgApplication` xuất hiện trong dòng JSON hợp lệ.
  - Giới hạn: giá trị không nháy có khoảng trắng chỉ bị che tới khoảng trắng đầu tiên; khóa ngoài danh sách (ví dụ `vnp_SecureHash`, `mac`) không được che; `StructuredArguments` và `Markers` không được dùng trong code nên không có đường đưa tên trường nhạy cảm vào JSON.

### 2.2 Tách quyền user DB (§5.4, §14.3)
- **Làm gì:** tạo hai role `rentgg_app` (không có TRUNCATE/DROP) và `rentgg_migrator` (chạy Flyway). Thêm vào `deploy/docker-compose.prod.yml`, `deploy/.env.prod.example`, và cấu hình `spring.datasource` (tách user cho migration và runtime).
- **Hiện trạng:** chưa có `rentgg_app` hay `rentgg_migrator` trong `deploy/` và `backend/`.
- **Xong khi:** migration chạy bằng `rentgg_migrator`, ứng dụng chạy bằng `rentgg_app`, và `rentgg_app` bị từ chối khi `TRUNCATE`.
- **Kiểm thử:** `[M]` với Postgres cục bộ (tạo hai role trong DB test); `[S]` không áp dụng.

### 2.3 Swagger và API docs trên staging/prod (§11.1)
- **Hiện trạng:** `application.yml` đã tắt `springdoc` cho profile `prod`. `StartupSafetyCheck` đã nhận diện `staging` là profile hợp lệ, nhưng `application.yml` chưa có khối `staging`, nên Swagger vẫn bật trên staging. Đây là việc thật, không phải việc tùy chọn.
- **Làm gì:** thêm khối `on-profile: staging` với `springdoc.api-docs.enabled: false` và `springdoc.swagger-ui.enabled: false`, giống khối prod.
- **Xong khi:** `GET /swagger-ui.html` và `/v3/api-docs` trả 404 trên prod (và staging nếu có).
- **Kiểm thử:** `[M]` bằng test MockMvc với profile `prod`.

### 2.4 TOTP bắt buộc cho SUPPORT, ADMIN, FINANCE (Phase 1, §4.2)
- **Làm gì:** thêm MFA TOTP, bắt buộc cho ba role trên. Dùng thư viện TOTP chuẩn RFC 6238.
- **Xong khi:** staff không có TOTP bị chặn truy cập trang quản trị; test với mã đúng, sai và đã dùng.
- **Kiểm thử:** `[M]`.
- **Ghi chú:** tài liệu xếp mục này ở Phase 1. Có thể bỏ qua nếu bạn chưa có tiền thật đi qua.

---

## 3. Các mục còn lại của §4.1

Lấy đúng thứ tự mức độ từ `RentGG_Implementation_Status.md` §4.1, không phân tích lại từ đầu. Mỗi mục làm theo mẫu sau:

| # | Mục | Khu vực | Xong khi | Kiểm thử |
|---|---|---|---|---|
| 5 | Re-auth khi xem credentials (D5) | `gameaccount/` (credentials) | Cờ `rentgg.credentials.require-reauth` có tác dụng | `[M]` |
| 6 | Rate limit đăng nhập lũy tiến (§12.5) | `identity/` | Thời gian chờ tăng sau nhiều lần sai | `[M]` |
| 7 | Tín hiệu lạm dụng khi vượt giới hạn xem credentials | `identity/`, `audit/` | Ghi `abuse_signals` khi vượt giới hạn | `[M]` |
| 8 | Metrics còn thiếu (§16.2) | `shared/` (metrics) | Ba metric xuất hiện trên `/actuator/prometheus` | `[M]` |
| 9 | MDC `userId` và `orderId` | `shared/` | Log có hai trường này | `[M]` |
| 10 | Cảnh báo outbox kẹt và refund lỗi | `jobs/` | Có rule alert (file cấu hình) | `[M]` |
| 11 | Endpoint quản trị còn thiếu | `identity/`, `approval/` | Danh sách chờ `verify-recovery`, danh sách user, tra audit | `[M]` |
| 12 | Kháng nghị quyết định | `dispute/` | Chủ account có thể kháng nghị một lần | `[M]` |
| 13 | Hồ sơ thông báo pháp lý (UC-20) | mới | Bảng và endpoint tiếp nhận, có hạn phản hồi | `[M]` |
| 14 | Cảnh báo quá hạn phân xử (`deadline_at`) | `dispute/` | Job đánh dấu quá hạn | `[M]` |
| 15 | Giới hạn body JSON 1 MB (§12.10) | `shared/` (web) | Body lớn hơn bị 413 | `[M]` |
| 16 | Template thông báo theo sự kiện | `notification/` | Mỗi sự kiện có tiêu đề và nội dung riêng | `[M]` |
| 17 | CI backend và frontend | `.github/` | Xem mục 1.2 | CI |

---

## 4. Tích hợp bên ngoài (làm bằng mock trước)

Mỗi tích hợp có hai checkbox: **`[M]` đạt với mock** và **`[S]` đạt với sandbox thật**. Mục `[S]` để trống cho đến khi bạn có khóa (Phụ lục A).

### 4.1 Cổng thanh toán (PSP)
Có năm lớp: `VnpayGateway`, `MomoGateway`, `ZalopayGateway`, `PayosGateway`, và `PspHttp` (lớp HTTP dùng chung). Thư mục: `backend/src/main/java/com/rentgg/payment/infrastructure/psp/`.

Cho **mỗi** adapter, viết contract test với mock HTTP server (WireMock hoặc MockWebServer):

- [ ] **Tạo thanh toán:** request có đúng chữ ký, đúng tham số; response thành công trả URL thanh toán.
- [ ] **Webhook hợp lệ:** chữ ký đúng → xác nhận thanh toán, cập nhật `payment_intents`.
- [ ] **Webhook sai chữ ký:** → từ chối, ghi audit, không đổi trạng thái.
- [ ] **Webhook phát lại (replay):** cùng một sự kiện gửi hai lần → chỉ xử lý một lần (idempotent).
- [ ] **Hoàn tiền toàn phần và một phần:** số tiền đúng, không vượt số đã trả của từng payment.
- [ ] **Timeout / 5xx:** retry có giới hạn, không đổi trạng thái sai.
- [ ] **`[S]` Sandbox thật:** tạo thanh toán thật trên sandbox, webhook thật, hoàn tiền thật. *Để trống đến khi có khóa.*

Thứ tự đề xuất: **PayOS trước** (theo `deploy/.env.prod.example`); các adapter còn lại chỉ `[M]`.

### 4.2 Ảnh và video trên S3/R2
- **Hiện trạng:** `MediaObjectStorageIT` đã dùng `InMemoryStorage` (mock), không gọi S3 thật.
- **Làm gì:**
  - `[M]`: giữ `InMemoryStorage` cho test thường; thêm test `S3ObjectStorage` với MinIO hoặc một server S3-tương thích chạy cục bộ (Docker, nếu daemon đã bật).
  - `[M]`: kiểm tra backfill chuyển đúng ảnh cũ từ `db` sang storage, không mất dữ liệu.
  - `[S]`: upload và đọc lại trên bucket R2 thật `rentgg-media`.
- **Xong khi:** test backfill pass; `[S]` để trống đến khi có token R2.

### 4.3 Kênh thông báo khác (email, push)
- **Hiện trạng:** không tìm thấy adapter email hay push trong code. Nếu sau này thêm, làm theo mẫu 4.1 (`[M]` + `[S]`).

---

## 5. Dry-run triển khai production

- **Làm gì:**
  1. Bật Docker Desktop (hiện daemon chưa chạy trên máy này; lệnh `docker version` chỉ thấy client 29.9.2).
  2. Dựng `deploy/docker-compose.prod.yml` cục bộ với `.env.prod` giả (dùng `RENTGG_DOMAIN=localhost` và `caddy internal`).
  3. Chạy `deploy/backup/backup.sh`, xóa DB thử, chạy `deploy/restore.sh`, kiểm tra số bản ghi.
  4. Chạy `deploy/make-admin.sh` để tạo admin và đăng nhập được.
- **Xong khi:** ba lệnh trên chạy không lỗi; bản ghi sau restore khớp trước backup.
- **Kiểm thử:** `[M]`. Cloudflare và R2 để `[S]` cho sau.

---

## 6. Mobile

### 6.1 iOS: sửa lỗi build trên CI
- **Làm gì:** xem log `ios.yml` (macOS-15), sửa lỗi biên dịch Xcode nếu có. `RentGGCore` đã có 39 unit test và 6 test tích hợp (27/10).
- **Xong khi:** `ios.yml` xanh.
- **Kiểm thử:** CI. Chạy trên Simulator thủ công cần máy Mac, để sau.

### 6.2 Android: ký bản release từ biến môi trường
- **Làm gì:** đọc keystore path, alias, mật khẩu từ biến môi trường hoặc `~/.gradle/gradle.properties` trên máy (ngoài repo). `android/gradle.properties` **được commit** trong repo, chỉ chứa cấu hình build, không đặt khóa ký vào đó; không đưa khóa vào `build.gradle.kts`.
- **Xong khi:** `./gradlew assembleRelease` tạo APK đã ký khi có biến; khi thiếu, rơi về bản unsigned như hiện tại.
- **Kiểm thử:** `[M]` (build). Keystore thật là `[S]`.

### 6.3 Android: E2E trên emulator hoặc điện thoại thật
- **Hiện trạng:** `android/PROGRESS.md` ghi máy báo `VirtualizationFirmwareEnabled = False`. Trạng thái này **chưa xác minh lại** hôm nay.
- **Làm gì:** kiểm tra BIOS ảo hóa; nếu bật được, chạy emulator và trỏ `10.0.2.2:8080`. Nếu không, dùng điện thoại thật qua IP LAN.
- **Xong khi:** đăng nhập, đặt đơn, xem đơn chạy được với backend dev.

---

## 7. Làm mới tài liệu

- **Làm gì:** cập nhật `RentGG_Implementation_Status.md`, `RentGG_Frontend_Status.md`, `RentGG_iOS_Status.md`:
  - 30 migration (không phải 29);
  - CI backend và web đã có (kiểm tra lại sau mục 1.2);
  - tình trạng Docker, ảo hóa Android;
  - trạng thái các mục §4.1 sau khi làm.
- **Xong khi:** mỗi tài liệu có ngày cập nhật mới và không còn mục nào mâu thuẫn với code.
- **Kiểm thử:** đọc lại; đối chiếu với `git log`.

---

## Phụ lục A — Giá trị cần điền khi bạn đăng ký xong

Điền vào `deploy/.env.prod` (prod) và `.env` (dev sandbox). **Không** commit các file này.

| Biến | Mục đích | Được mock bởi | Smoke test sau khi điền |
|---|---|---|---|
| `RENTGG_DOMAIN` | Tên miền công khai | Dùng `localhost` trong dry-run | `curl -I https://<domain>` trả 200 qua Caddy |
| `ACME_EMAIL` | Email Let's Encrypt | Không cần mock | Caddy log "certificate obtained" |
| `DB_PASSWORD` | Mật khẩu Postgres | Dev dùng `rentgg_dev` | Backend khởi động, `/actuator/health` = UP |
| `RENTGG_JWT_SECRET` | Ký JWT (≥ 32 byte) | Test dùng giá trị cố định | Đăng nhập ra access token |
| `RENTGG_CREDENTIAL_KEYS` | Khóa mã hóa mật khẩu tài khoản game (`keyId:base64`) | Test dùng `devkey` | Lưu một account, xem credentials ra đúng. **Lưu một bản ngoài VPS.** |
| `RENTGG_CREDENTIAL_ACTIVE_KEY_ID` | Khóa đang dùng | Test: `devkey` | Như trên |
| `RENTGG_PAYMENT_WEBHOOK_SECRET` | Ký webhook nội bộ (≥ 32 ký tự) | Test dùng chuỗi cố định | Webhook giả có chữ ký đúng được nhận |
| `RENTGG_CORS_ALLOWED_ORIGINS` | Domain web được gọi API | Không cần | Preflight từ web trả header đúng |
| `RENTGG_PUBLIC_BASE_URL` | URL backend công khai (HTTPS) | Dev: `localhost:8080` | Trang thanh toán của provider gọi lại được |
| `RENTGG_FRONTEND_BASE_URL` | URL web nơi người trả tiền quay về (HTTPS) | Dev: `localhost:5273` | Redirect sau thanh toán về đúng trang |
| `RENTGG_PAYMENT_PROVIDER` | Chọn provider (`FAKE`, `PAYOS`, `VNPAY`, `MOMO`, `ZALOPAY`) | Dev: `FAKE`; test mock từng adapter | Khởi động prod với `FAKE` phải bị từ chối (mục 2.0) |
| `RENTGG_PAYMENT_FAKE_ENABLED` | Bật giả lập (chỉ dev) | Dev: `true` | Prod phải là `false` |
| `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY` | PayOS (ưu tiên) | Mock HTTP server (mục 4.1) | Tạo link thanh toán sandbox trả `checkoutUrl`; webhook thật được ký đúng |
| `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` | VNPay sandbox (đăng ký tại sandbox.vnpayment.vn) | Mock (mục 4.1) | Tạo URL thanh toán; IPN thật được nhận tại `/api/v1/payments/webhook/vnpay` |
| `MOMO_PARTNER_CODE`, `MOMO_ACCESS_KEY`, `MOMO_SECRET_KEY` | MoMo business test | Mock (mục 4.1) | Tạo yêu cầu, nhận `payUrl` |
| `ZALOPAY_APP_ID`, `ZALOPAY_KEY1`, `ZALOPAY_KEY2` | ZaloPay sandbox | Mock (mục 4.1) | Tạo đơn, nhận `order_url` |
| `RENTGG_MEDIA_STORAGE` | `db` (dev) hoặc `s3` (prod) | Test dùng `InMemoryStorage` | Prod đặt `s3`, upload ảnh thành công |
| `RENTGG_S3_ENDPOINT` | Endpoint R2 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` | MinIO cục bộ (mục 4.2) | Upload và đọc lại object trong `rentgg-media` |
| `RENTGG_S3_BUCKET` | Bucket `rentgg-media` | MinIO | Như trên |
| `RENTGG_S3_ACCESS_KEY`, `RENTGG_S3_SECRET_KEY` | Token R2 chỉ cho `rentgg-media` | MinIO | Như trên |
| `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY` | Sao lưu lên bucket `rentgg-backup` (token riêng) | Dry-run mục 5 dùng thư mục cục bộ | Chạy `backup.sh`, file xuất hiện trong bucket |
| `BACKUP_HOUR`, `BACKUP_KEEP_DAYS` | Lịch và thời gian giữ bản sao | Không cần | File cũ hơn số ngày bị xóa |
| `API_BASE_URL` (iOS Release, `ios/project.yml`) | URL API cho bản phát hành | Debug dùng `localhost:8080` | Bản Release gọi được `/api/v1/listings` |
| Keystore Android (path, alias, mật khẩu) | Ký APK release (`gradle.properties` cục bộ) | Build unsigned | `assembleRelease` tạo APK đã ký |

Ghi chú:
- `RENTGG_PAYMENT_PROVIDER` và `RENTGG_PAYMENT_FAKE_ENABLED` là hai biến quan trọng nhất khi chuyển sang production; kiểm tra lại trước mỗi lần deploy.
- Khi tên biến khác với tên trong file mẫu, ưu tiên file mẫu trong repo (`.env.example`, `deploy/.env.prod.example`) và `application.yml`.
