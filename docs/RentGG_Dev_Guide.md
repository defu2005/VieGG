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
- **Kết quả (2026-11-20, nhánh `claude/row-03-db-roles`): ĐÃ LÀM, trừ phần Docker.**
  - Thay đổi: `deploy/db/roles.sql` (idempotent, không mật khẩu, không tên DB; `GRANT CONNECT, CREATE ON DATABASE` lấy từ `current_database()` vì V1 cần extension `btree_gist`); `deploy/db/init-roles.sh` (đặt mật khẩu qua biến psql); compose: app dùng `rentgg_app`, `SPRING_FLYWAY_USER=rentgg_migrator`; `restore.sh` dùng `--role=rentgg_migrator`; `.env.prod.example` có hai biến mới; `application.yml` chỉ thêm chú thích.
  - Lệch khỏi thiết kế ban đầu: không mount cả `deploy/db` vào `initdb.d`, vì entrypoint chạy mọi `.sql` ở đó và `roles.sql` sẽ chạy trước khi có mật khẩu. Chỉ `init-roles.sh` vào `initdb.d`; `roles.sql` mount vào `/opt/rentgg/db/`.
  - `DbRolesIT` (5 test, JDBC thuần): Flyway chạy bằng `rentgg_migrator` từ DB rỗng; `rentgg_app` có DML, không có TRUNCATE trên mọi bảng; `SELECT` thật; TRUNCATE, DROP, CREATE, ALTER đều bị `42501`; migrator vẫn TRUNCATE được; `installed_by` của lịch sử Flyway là `rentgg_migrator`. Không đặt `TEST_DB_URL`: skip có lý do (`@EnabledIf`). Trong CI (`CI` đặt): fail nếu thiếu DB hoặc quyền tạo role.
  - Negative control: thêm `ALTER DEFAULT PRIVILEGES ... GRANT TRUNCATE ON TABLES TO rentgg_app` làm 2 test fail. Control đầu tiên (`GRANT ... ON ALL TABLES`, chạy trước khi có bảng) không có tác dụng và bị loại.
  - Full suite bình thường (`rentgg`): 226 test, 0 fail, 0 lỗi.
  - Full suite với `TEST_DB_USERNAME=rentgg_app`, Flyway bằng `rentgg_migrator`, trên DB chuẩn bị bằng `roles.sql` (auth trust): 221 test, 0 fail, 0 lỗi. Lịch sử Flyway: `rentgg_migrator`; bảng thuộc `rentgg_migrator`.
  - Auth scram-sha-256 (TCP): mật khẩu đúng đăng nhập được, sai bị từ chối (`init-roles.sh` trên DB mới). `DbRolesIT` 5/5 và khởi động prod với `rentgg_app` đều chạy lại dưới scram và pass / Started.
  - Khôi phục: `pg_restore --no-owner` không có `--role` làm mọi bảng thành `rentgg`, và `rentgg_migrator` mất quyền đọc `flyway_schema_history` (đã chứng minh). Với `--role=rentgg_migrator` từ DB đã chuẩn bị roles: bảng thuộc migrator, quyền app đúng, migrator đọc được 30 dòng lịch sử.
  - Giới hạn: phần Docker chưa chạy (không có daemon); compose chỉ kiểm tra cú pháp YAML. Volume đã có dữ liệu cần làm tay (xem `docs/RentGG_Production_Deploy.md` B1a). `rentgg_app` có DML trên `flyway_schema_history`. Hàm trigger vẫn thuộc `rentgg`. Cục bộ: role `rentgg` được đặt SUPERUSER (CI cũng là superuser); `DbRolesIT` đặt lại mật khẩu cả hai role trên toàn cluster.

### 2.3 Swagger và API docs trên staging/prod (§11.1)
- **Hiện trạng:** `application.yml` đã tắt `springdoc` cho profile `prod`. `StartupSafetyCheck` đã nhận diện `staging` là profile hợp lệ, nhưng `application.yml` chưa có khối `staging`, nên Swagger vẫn bật trên staging. Đây là việc thật, không phải việc tùy chọn.
- **Làm gì:** thêm khối `on-profile: staging` với `springdoc.api-docs.enabled: false` và `springdoc.swagger-ui.enabled: false`, giống khối prod.
- **Xong khi:** `GET /swagger-ui.html` và `/v3/api-docs` trả 404 trên prod (và staging nếu có).
- **Kiểm thử:** `[M]` bằng test MockMvc với profile `prod`.
- **Kết quả (2026-11-20, nhánh `claude/row-04-swagger-off`): ĐÃ LÀM.**
  - Thêm khối `staging` trong `application.yml` (api-docs và swagger-ui đều tắt), giống prod.
  - `ProdApiDocsDisabledIT`, `StagingApiDocsDisabledIT`: context thật với giá trị mock qua `StartupSafetyCheck`; `/v3/api-docs`, `/v3/api-docs/swagger-config`, `/swagger-ui.html`, `/swagger-ui/index.html` đều 404. Cần `TEST_DB_URL`.
  - Control `ApiDocsEnabledInTestProfileIT` (profile `test`): `/v3/api-docs` trả 200.
  - Negative control: xóa khối `staging` làm `StagingApiDocsDisabledIT` fail.
  - Full suite: 229 test, 0 fail, 1 lỗi: `MoneyAndApprovalIT.concurrentBooking_exactlyOneWins` (deadlock đã biết, §1.2).

### 2.4 TOTP bắt buộc cho SUPPORT, ADMIN, FINANCE (Phase 1, §4.2)
- **Làm gì:** thêm MFA TOTP, bắt buộc cho ba role trên. Dùng thư viện TOTP chuẩn RFC 6238.
- **Xong khi:** staff không có TOTP bị chặn truy cập trang quản trị; test với mã đúng, sai và đã dùng.
- **Kiểm thử:** `[M]`.
- **Ghi chú:** tài liệu xếp mục này ở Phase 1. Có thể bỏ qua nếu bạn chưa có tiền thật đi qua.
- **Kết quả (2026-11-20, nhánh `claude/row-05-staff-totp`, PR #6): ĐÃ LÀM** (backend, web, kiểm tra trên trình duyệt).
  - Thứ tự hàng đợi cloud không có §2.4, nhưng brief đưa vào hàng đợi, nên làm theo brief.
  - Cơ chế: cờ `rentgg.security.staff-mfa-required` (`RENTGG_STAFF_MFA_REQUIRED`, mặc định true; false ở `dev`; `StartupSafetyCheck` từ chối false trên staging/prod). Staff (vai trò có `roles.is_staff`) chưa xác thực có `ROLE_*` nhưng không có quyền nào, nên endpoint `@PreAuthorize` trả 403. Helpdesk đọc quyền staff của chính người gọi từ token (trước đây đọc từ DB: lỗ hổng đã sửa, có test và negative control).
  - Claim `mfa` trong access token; `refresh_tokens.mfa_verified` giữ qua refresh; đổi mật khẩu và dev time travel giữ trạng thái phiên hiện tại.
  - TOTP: `dev.samstevens.totp:totp:1.7.1` (6 số, 30 giây, SHA-1), cửa sổ ±1 bước, mỗi bước một lần (cập nhật có điều kiện), khoá 15 phút sau 5 lần sai. Bí mật mã hoá bằng `CredentialCipher` (khoá theo user id).
  - Đăng nhập: staff đã bật TOTP phải gửi `totpCode` (`MFA_REQUIRED`, `MFA_INVALID`, `MFA_LOCKED`). `AuthService.login` có `noRollbackFor` để ghi nhận lần sai. Staff chưa bật đăng nhập không xác thực và chỉ có thể bật TOTP.
  - `/api/v1/me`: quyền theo token (rỗng với phiên chưa xác thực) và `mfa: {required, enrolled, verified}`.
  - Web: `LoginPage` hiện ô mã khi `MFA_REQUIRED`; `StaffMfaPanel` thay trang quản trị và trang chủ staff khi phiên chưa xác thực (bật TOTP, hoặc đăng nhập lại kèm mã).
  - Migration `V31__staff_mfa.sql`: `refresh_tokens.mfa_verified`, bảng `user_totp`. Áp dụng được dưới `rentgg_migrator`.
  - Mobile: Android (`ignoreUnknownKeys = true`) và iOS (`JSONDecoder` mặc định) bỏ qua khoá lạ, nên `mfa` không làm hỏng ứng dụng. Nhưng ứng dụng di động chưa có ô nhập mã: staff đăng nhập bằng ứng dụng đó sẽ nhận `MFA_REQUIRED` và không vào được.
  - Test: `StaffMfaIT` 15 test (quyền 403/200; mã đúng, sai, đã dùng; ±1 chấp nhận, ±2 từ chối; đăng nhập đồng thời chỉ một thắng; khoá sau 5 lần sai; refresh giữ trạng thái; secret mã hoá; endpoint bật TOTP; mọi case staff của RbacIT với token chưa xác thực; `/me`; helpdesk), `TotpVectorsTest` (RFC 6238 Appendix B), `StartupSafetyCheckTest` (cờ tắt bị từ chối). Negative control: bỏ cổng trong converter và bỏ kiểm tra helpdesk đều làm test tương ứng fail.
  - Full suite: 247 test, 0 fail, 0 lỗi. Backend CI trên `27d356d` (run `44812241774`): xanh, 247 test. Web CI: xanh.
  - Frontend: `npm ci`, typecheck, build đều pass.
  - Kiểm tra trên trình duyệt (Playwright, backend `dev` với cờ bật, DB dev đã seed): **ĐẠT**. Admin chưa bật TOTP: thấy panel, tạo khoá, xác nhận mã; đăng xuất; chờ bước mới; đăng nhập bằng mật khẩu nhận ô mã; mã sai báo lỗi; mã đúng vào `/admin/listings` có dữ liệu. Script: `scripts/e2e/staff-mfa.js` (chạy với backend dev và Vite trên 5273; đọc `playwright` từ môi trường). Lưu ý: onboarding overlay chặn thao tác cho cả tài khoản staff, phải đóng trước khi làm việc trên panel.
  - Lỗi có sẵn, không do hàng 5: boot `dev` trên DB mới thất bại vì `DevDataSeeder.paid` ném `Số dư ví không đủ` (trên `origin/dev` cũng vậy). Đã queue một task để sửa.
  - Vận hành: lần đầu bật TOTP chỉ dựa vào mật khẩu; mất thiết bị cần reset tay (`docs/RentGG_Production_Deploy.md` B1b); phiên cũ không có `mfa` phải đăng nhập lại.

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

- **Kết quả (2026-11-20, nhánh `claude/row-09-credentials-reauth`): ĐÃ LÀM `[M]`.** Trước đây cờ `rentgg.credentials.require-reauth` không được đọc ở đâu cả; nay có tác dụng. Bật cờ: `GET /orders/{id}/credentials` trả 403 `REAUTH_REQUIRED` (không ghi `CREDENTIAL_VIEWED`, không đặt `credential_first_viewed_at`); `POST /orders/{id}/credentials` với `{password}` trả thông tin khi đúng mật khẩu, 403 `REAUTH_FAILED` khi sai. Mỗi lần thử (đúng hoặc sai) dùng một trong 5 lượt mỗi giờ theo người dùng (429 sau đó). Mọi lần từ chối ghi `CREDENTIAL_VIEW_DENIED` với lý do. Thứ tự kiểm tra: renter, tài khoản bị đình chỉ, cửa sổ thuê, re-auth, rồi giới hạn xem (lần từ chối không tốn lượt xem). Biến môi trường `RENTGG_CREDENTIALS_REQUIRE_REAUTH`, mặc định false. Test: `CredentialReauthIT` (5 test trong context riêng, pool 5, `@DirtiesContext`); điều khiển bỏ kiểm tra làm 3 test fail. Web: `OrderDetailPage` hỏi mật khẩu khi nhận `REAUTH_REQUIRED`; đã chạy `npm run typecheck` và `npm run build` (pass). **Chưa kiểm tra trên trình duyệt.** Khi cờ tắt (mặc định), POST trả thông tin bất kể mật khẩu gửi lên (`CredentialFlagOffIT`), và cả GET lẫn POST đều có `Cache-Control: no-store`. Khi cờ bật, các lần thành công cũng dùng lượt 5 lượt/giờ, nên renter chỉ xem được 5 lần mỗi giờ trên mọi đơn. iOS và Android chưa có bước này, nên cờ giữ false cho đến khi có.

- **Kết quả mục 6 (2026-11-20, nhánh `claude/row-10-login-backoff`): ĐÃ LÀM `[M]`.** §12.5 không đưa ngưỡng hay yêu cầu trạng thái bền vững, chỉ ghi "sai liên tiếp → chờ lũy tiến", nên dùng cơ chế đã chốt: `LoginBackoff` trong bộ nhớ, khoá giống limiter đăng nhập (IP và email viết thường), thời gian lấy từ `Clock`. Năm lần sai đầu miễn phí; sau đó chờ 30 s, nhân đôi đến trần 15 phút. Chỉ `INVALID_CREDENTIALS` được tính; `MFA_*` và `USER_SUSPENDED` không tính và không reset; phiên được cấp thì reset. Kiểm tra thời gian chờ trước khi xác minh mật khẩu (trả 429 `RATE_LIMITED`, không tính). Trạng thái quá một giờ không dùng bị quên; số khoá giới hạn 50 000. Theo instance và mất khi khởi động lại, giống limiter Bucket4j. Test: `LoginBackoffIT` (5): năm sai rồi đúng (429), chờ và đúng (200), sai lại (401); các lần chờ tăng dần, trần 15 phút; email không tồn tại có cùng chuỗi; IP hoặc email khác không bị ảnh hưởng; nhân viên đã bật TOTP gửi thiếu mã sáu lần không bị khoá. Điều khiển bỏ kiểm tra trước mật khẩu làm 3 test fail. Full suite: 305 test, 0 fail, 0 lỗi.

- **Kết quả mục 7 (2026-11-20, nhánh `claude/row-11-abuse-signal`): ĐÃ LÀM `[M]`.** Bảng `abuse_signals` có sẵn từ V1 (không có CHECK trên `type`, `order_id` không có FK tới `orders`), nên không cần migration mới. Khi chạm giới hạn xem thông tin (`CREDENTIAL_VIEW_RATE_LIMITED`) hoặc giới hạn re-auth (`CREDENTIAL_VIEW_REAUTH_RATE_LIMITED`), ghi tối đa một tín hiệu cho mỗi người dùng và đơn trong một giờ (kiểm tra `existsSince`), trong cùng transaction (có `noRollbackFor`), không dùng `recordIndependently`. Test: `CredentialViewLimitSignalIT` (10 lần xem không có tín hiệu; lần 11 trả 429 và có đúng một dòng; lần 12 vẫn một dòng; SUPPORT đọc được qua `/api/v1/admin/users/{id}/abuse-signals`), và `CredentialReauthIT` có thêm kiểm tra giới hạn re-auth. Điều khiển bỏ lời gọi ghi tín hiệu làm test fail. Full suite: 306 test, 0 fail, 0 lỗi.

## 4. Tích hợp bên ngoài (làm bằng mock trước)

Mỗi tích hợp có hai checkbox: **`[M]` đạt với mock** và **`[S]` đạt với sandbox thật**. Mục `[S]` để trống cho đến khi bạn có khóa (Phụ lục A).

### 4.1 Cổng thanh toán (PSP)
Có năm lớp: `VnpayGateway`, `MomoGateway`, `ZalopayGateway`, `PayosGateway`, và `PspHttp` (lớp HTTP dùng chung). Thư mục: `backend/src/main/java/com/rentgg/payment/infrastructure/psp/`.

Cho **mỗi** adapter, viết contract test với mock HTTP server (WireMock hoặc MockWebServer):

- [x] **Tạo thanh toán `[M]`:** request có đúng chữ ký, đúng tham số; response trả URL. PayOS, MoMo, ZaloPay có contract với mock server; VNPay kiểm tra URL với hash tính độc lập.
- [x] **Webhook hợp lệ `[M]`:** chữ ký đúng → xác nhận thanh toán, cập nhật `payment_intents`, cộng ví một lần. PayOS và MoMo qua endpoint; ZaloPay và VNPay qua `PaymentProvidersIT` và contract.
- [x] **Webhook sai chữ ký `[M]`:** → từ chối, ghi audit, không đổi trạng thái. PayOS (`PayosWebhookIT`), MoMo (`MomoWebhookIT`); ZaloPay và VNPay qua `PaymentProvidersIT`.
- [x] **Webhook phát lại (replay) `[M]`:** chỉ cộng một lần, kể cả replay đồng thời (PayOS, `PayosWebhookIT`).
- [ ] **Hoàn tiền toàn phần và một phần `[M]`:** *không triển khai ở mức PSP.* Không adapter nào gọi API hoàn tiền của PSP; hoàn tiền luôn cộng vào ví (`PaymentService.processRefund`). Ca trần qua nhiều payment được `OrderLifecycleIT` kiểm tra.
- [x] **Timeout / 5xx `[M]`:** một lần thử, không retry; trả `ProviderException` (PayOS, ZaloPay). Client gửi lại với cùng Idempotency-Key. Lỗi gửi về client còn chứa URL endpoint: xem ghi chú bên dưới.
- [ ] **`[S]` Sandbox thật:** tạo thanh toán thật trên sandbox, webhook thật, hoàn tiền thật. *Để trống đến khi có khóa.*

Thứ tự đề xuất: **PayOS trước** (theo `deploy/.env.prod.example`); các adapter còn lại chỉ `[M]`.

- **Kết quả (2026-11-20, nhánh `claude/row-06-psp-contract`): ĐÃ LÀM `[M]`; `[S]` chưa làm.**
  - Test contract với `MockWebServer` (không Spring): PayOS (`PayosGatewayContractTest` 9, `PspHttpFailureTest` 5), MoMo (`MomoGatewayContractTest` 8), ZaloPay (`ZalopayGatewayContractTest` 7), VNPay (`VnpayGatewayContractTest` 1, URL tự kiểm bằng HMAC-SHA512 độc lập).
  - Endpoint (context mặc định, Postgres): PayOS (`PayosWebhookIT` 5: hợp lệ cộng một lần, chữ ký sai không đổi gì và có audit, replay đồng thời cộng đúng một lần, số tiền khác có audit, top-up tới endpoint chết trả 502 và giữ số dư), MoMo (`MomoWebhookIT` 2: hợp lệ trả 204 cộng một lần, chữ ký sai trả 400). Hợp đồng của `PaymentProvidersIT` cập nhật vì PAYOS và MOMO nay có khoá test: danh sách provider và webhook MoMo rỗng (400 thay vì 404) và top-up PAYOS (502).
  - Chưa cấu hình (không Spring): khoá trống thì `isConfigured()` false, provider không hiện, `require` ném `PAYMENT_PROVIDER_UNAVAILABLE` (`UnconfiguredProvidersTest` 2).
  - Negative control, từng adapter (mỗi lần khôi phục code sau khi chạy):
    - PayOS: bỏ kiểm tra chữ ký callback → 2 test contract và 1 test endpoint fail; ký outbound bằng khoá khác → test outbound fail.
    - MoMo: bỏ kiểm tra chữ ký callback → test contract và `MomoWebhookIT` fail; ký outbound bằng khoá khác → test outbound fail.
    - ZaloPay: ký outbound bằng `key1` sai → test outbound fail; bỏ kiểm tra `key2` → test callback sai khoá/sai số tiền và test redirect sai số tiền fail. (Test redirect hợp lệ vẫn pass, đúng như mong đợi.)
    - VNPay: hash URL sai → `checkoutUrlCarriesTheDocumentedParametersAndAHashOverItsQuery` fail (surefire: `VnpayGatewayContractTest.java:41`). VNPay callback chỉ được kiểm tra bởi `PaymentProvidersIT`, không có contract riêng.
  - Ghi chú vận hành: `createTopup` trả nguyên văn thông báo lỗi của PSP (`PaymentProvidersIT`/`PayosWebhookIT`: "Không gọi được cổng thanh toán: I/O error on POST request for \"http://127.0.0.1:1/...\"") cho client. Thông báo này chứa URL endpoint. Chưa sửa trong hàng này; đã queue task.
  - Hoàn tiền: không adapter nào gọi API hoàn tiền của PSP. Hoàn tiền luôn cộng vào ví người thuê (`processRefund`). Ô "hoàn tiền toàn phần/một phần" ghi là **không triển khai ở mức PSP**. Ca trần qua nhiều payment đã có trong `OrderLifecycleIT` (số tiền hoàn chia theo các payment của đơn).
  - Quyết định: không thêm retry. Mỗi lần gọi PSP thất bại đúng một lần; client gửi lại với cùng Idempotency-Key.
  - Phát hiện: 5xx bị từ chối bởi request factory (HttpURLConnection ném IOException), không phải do adapter kiểm tra status. Test probe `PspHttpFailureTest` ghi lại hành vi này.
  - PayOS: `code` trong envelope callback nằm ngoài chữ ký (chỉ `data` được ký).
  - Giới hạn: test chỉ dùng mock. Đối chiếu với nhà cung cấp thật là `[S]`, chưa xác minh.
  - CI: thêm bước thử lại tải Maven wrapper ba lần (`.github/workflows/backend.yml`).
  - Full suite: 286 test, 0 fail, 0 lỗi.

### 4.2 Ảnh và video trên S3/R2
- **Hiện trạng:** `MediaObjectStorageIT` đã dùng `InMemoryStorage` (mock), không gọi S3 thật.
- **Làm gì:**
  - `[M]`: giữ `InMemoryStorage` cho test thường; thêm test `S3ObjectStorage` với MinIO hoặc một server S3-tương thích chạy cục bộ (Docker, nếu daemon đã bật).
  - `[M]`: kiểm tra backfill chuyển đúng ảnh cũ từ `db` sang storage, không mất dữ liệu.
  - `[S]`: upload và đọc lại trên bucket R2 thật `rentgg-media`.
- **Xong khi:** test backfill pass; `[S]` để trống đến khi có token R2.
- **Kết quả (2026-11-20, nhánh `claude/row-07-s3-storage`): ĐÃ LÀM `[M]`; `[S]` để trống.**
  - `[M]` S3 thật (giao thức, chữ ký): `s3proxy` 2.3.0 chạy trong JVM, Maven resolve được, CI không cần binary. Đã thử: secret sai bị 403 (`S3Exception`), bucket không có báo `NoSuchBucketException`. Chữ ký được kiểm tra, không phải "chấp nhận mọi thứ".
  - `S3ObjectStorageTest` (5, không Spring): round trip 5 MiB + 7 byte chứa đủ 256 giá trị byte; ghi đè, xoá rồi đọc báo lỗi, xoá khoá không tồn tại không lỗi; khoá đúng định dạng `MediaStore` (`listing-images/<parent>/<uuid>`); secret sai và bucket thiếu đều lỗi rõ ràng.
  - Backfill (`MediaObjectStorageIT`, dùng context có sẵn, không thêm context): 5 hàng cũ đếm đúng số lượng, băm SHA-256 của mỗi ảnh đọc qua endpoint công khai khớp bản gốc, lần chạy thứ hai chuyển 0. Lỗi giả lập ở put thứ 2: hàng lỗi giữ byte và vẫn đọc được qua endpoint, lần chạy sau hoàn tất. Dữ liệu cũ được chèn trực tiếp bằng SQL (không qua endpoint upload, vì upload sẽ đưa byte lên bucket ngay); mỗi bảng backfill có một hàng: `listing_images`, `dispute_attachments`, `support_attachments`. Khoá và byte được kiểm tra riêng cho hai bảng dispute và support.
  - Code backfill xoá byte trong DB **sau** khi put thành công (đúng).
  - Điều khiển: bỏ dòng `storage.put` trong backfill làm 3 test backfill fail. Code đã khôi phục.
  - Full suite: 293 test, 0 fail, 1 lỗi deadlock đã biết.
  - Chưa làm: R2 thật (`[S]`).

### 4.3 Kênh thông báo khác (email, push)
- **Hiện trạng:** không tìm thấy adapter email hay push trong code. Nếu sau này thêm, làm theo mẫu 4.1 (`[M]` + `[S]`).
- **Kết quả kiểm tra (2026-11-20, nhánh `claude/row-08-notify-audit`): không có adapter kênh ngoài.** Đã tìm trong `backend/src/main` và `pom.xml` các từ khoá `JavaMailSender`, `javax.mail`, `jakarta.mail`, `smtp`, `firebase`, `APNs`, `fcm`, `webpush`, `mailgun`, `sendgrid`: không có kết quả.
  - Thông báo hiện chỉ là bản ghi trong bảng `notifications` (V6), tạo từ sự kiện outbox bởi `NotificationService` (có tiêu đề tiếng Việt cho từng sự kiện), đọc qua `/api/v1/notifications` (danh sách, đánh dấu đã đọc, đánh dấu tất cả, đếm chưa đọc).
  - Không tạo adapter mới (theo brief). Việc gửi email hoặc push sẽ làm theo mẫu 4.1 khi có yêu cầu.

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
| `RENTGG_CREDENTIALS_REQUIRE_REAUTH` | Bật nhập lại mật khẩu RentGG khi xem thông tin đăng nhập (D5) | Dev/test: `false` | Bật, GET trả `REAUTH_REQUIRED`; POST với mật khẩu đúng trả thông tin |
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
