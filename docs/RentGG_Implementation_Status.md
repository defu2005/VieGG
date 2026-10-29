# RentGG — Báo cáo tình trạng triển khai Backend Phase 0

**Ngày:** 2026-10-26 · **Rà soát lại:** 2026-10-29
**Đối chiếu với:** System Design v1.2, Use Case v8, Validation Checklist v1.0
**Mã nguồn:** `C:\Users\defu\Documents\RentGG` (chưa khởi tạo git, chưa commit)

---

## 1. Tóm tắt

| Hạng mục | Trạng thái |
|---|---|
| Backend MVP (Java 21, Spring Boot 3.3.5, PostgreSQL 16) | **Xong.** Biên dịch sạch, chạy được với profile `dev` |
| Schema DB (29 migration Flyway, V1–V29) | **Xong** theo §5; các bản sau V7 bổ sung RBAC, ảnh listing, gia hạn đơn, helpdesk (xem 2.2) |
| Use Case P0 | **Đủ các luồng chính**. Một số chi tiết phụ còn thiếu (mục 4) |
| Kiểm thử | Khoảng 30 file test (tích hợp, unit, kiến trúc, phân quyền), vẫn phủ các kịch bản bắt buộc T1–T18 (§18.2). **Số liệu "31/31" của báo cáo cũ đã lỗi thời; cần chạy lại `mvn test` để có kết quả mới** |
| Đóng gói | Có Dockerfile backend và service `app` trong docker-compose. **Chưa có** Dockerfile/service cho frontend và CI backend. Máy dev dùng PostgreSQL portable thay Docker |
| Adapter PSP | Đã có code cho MoMo, PayOS, VNPay, ZaloPay (`payment/infrastructure/psp`), **chưa xác nhận với sandbox của nhà cung cấp** |
| Việc nghiệp vụ ngoài code (checklist, phỏng vấn) | **Chưa làm.** Đây là việc của đội RentGG |

---

## 2. Những gì đã làm

### 2.1. Hạ tầng và kiến trúc

- Modular monolith gồm 15 module theo §4.3, mỗi module tách `api / application / domain / infrastructure`: `identity`, `catalog`, `gameaccount`, `listing`, `order`, `payment`, `ledger`, `deposit`, `wallet`, `dispute`, `approval`, `notification`, `audit`, `shared`, `jobs` (+ `config`).
- Quy tắc phụ thuộc §4.4 được kiểm tra tự động bằng ArchUnit (`ArchitectureTest`):
  - Module tầng dưới không gọi module tầng trên.
  - Module này không truy cập repository của module khác.
  - Trường hợp tầng dưới cần phản ứng với tầng trên thì dùng interface ngược (port): `PaymentCapturedHandler`, `OrderLifecycleListener`, `StaffDirectory`.
- Mọi logic thời gian đi qua bean `Clock`; test dùng `MutableClock` để tua thời gian.
- Outbox: bảng `outbox_events` được phát thành Spring `ApplicationEvent`, có retry kèm backoff, consumer idempotent.
- Job định kỳ chạy bằng `@Scheduled` + ShedLock JDBC, mỗi phút một lần. Đủ 9 job của §9, thêm `retryFailedRefunds`. Mỗi bản ghi xử lý trong một transaction riêng, chọn bằng `FOR UPDATE SKIP LOCKED`.
- Lỗi trả về JSON thống nhất theo §11.3, có `traceId`. Lỗi DB được ánh xạ sang lỗi nghiệp vụ, ví dụ `23P01` → `SLOT_UNAVAILABLE`.
- Có Swagger UI (`/swagger-ui.html`) và Actuator (`health`, `info`, `prometheus`).
- Log JSON (logstash encoder) cho staging/prod, log dạng text cho dev/test.
- Ứng dụng từ chối khởi động nếu thiếu hoặc ngắn secret, hoặc dùng secret mặc định của dev trên staging/prod (§14.3).

### 2.2. Cơ sở dữ liệu (`src/main/resources/db/migration`)

| Migration | Nội dung |
|---|---|
| V1 | `btree_gist`, `users`, `user_roles`, `refresh_tokens`, `kyc_submissions`, `abuse_signals` |
| V2 | `games` (đủ tham số chính sách §5.2 kèm CHECK §14.1), `game_accounts`, `credential_versions`, `listings` |
| V3 | `orders`: `rental_period` sinh tự động, **exclusion constraint chống đặt trùng**. Thêm `deposits` (UNIQUE `order_id`, CHECK tổng), `payment_intents`, `refunds` |
| V4 | `ledger_accounts`, `ledger_transactions` (idempotency key), `ledger_postings`. Seed 5 tài khoản hệ thống |
| V5 | `disputes` (tối đa 1 dispute OPEN mỗi loại mỗi đơn), `withdrawals`, `approval_requests` (CHECK four-eyes) |
| V6 | `audit_logs`, `notifications`, `outbox_events`, `shedlock` |
| V7 | Trigger append-only (chặn UPDATE/DELETE/TRUNCATE) cho ledger và audit. Constraint trigger deferred bảo đảm mỗi bút toán có tổng = 0 |
| V8 | Phân quyền RBAC (vai trò, quyền) |
| V9 | Ảnh listing |
| V10–V29 | Gồm đính kèm bằng chứng khiếu nại (V13), gia hạn đơn (V14), helpdesk (V28)… Danh sách đầy đủ: `backend/src/main/resources/db/migration` |

### 2.3. Độ phủ Use Case P0

| UC | Nội dung | Trạng thái | Ghi chú |
|---|---|---|---|
| UC-01 | Đăng ký, đăng nhập, xác minh | Xong | BCrypt 12. JWT HS256 sống 15'. Refresh token xoay vòng, phát hiện dùng lại. Chặn dưới 18 tuổi. Không bắt buộc KYC (endpoint nộp/duyệt KYC vẫn còn nhưng không chặn giao dịch) |
| UC-02 | Tìm kiếm, lọc | Xong | Lọc theo game, giá, khung giờ trống (`NOT EXISTS` trên `rental_period`). Trang chi tiết có chính sách và lịch bận |
| UC-03 | ToS theo game | Xong | Fail-closed. Mở lại game `PROHIBITED/SUSPENDED` phải qua dual approval `GAME_ALLOW` (trả 202) |
| UC-04 | Tạo, sửa listing | Xong | Kiểm tra tuổi, khoảng cọc, account đã xác minh khôi phục. Sửa listing `ACTIVE` thì quay về `PENDING_REVIEW` |
| UC-05 | Kiểm duyệt listing | Xong | Duyệt, từ chối, đình chỉ, có audit. Thiếu luồng kháng nghị của người đăng |
| UC-06 | Tạo đơn, giữ chỗ | Xong | Giữ chỗ bằng INSERT + exclusion constraint. Idempotency-Key. Có `policy_snapshot`. Cọc riêng cho từng đơn |
| UC-07 | Thanh toán, phí | Xong | Renter trả thuê + cọc, hoa hồng trừ từ tiền thuê. Webhook HMAC idempotent. Thanh toán muộn được hoàn 100% |
| UC-08 | Bàn giao credentials | Xong | AES-256-GCM, AAD theo account và version. Chỉ renter xem được, trong khung giờ thuê, tối đa 10 lần/giờ, mỗi lần đều audit, `no-store`. Hỗ trợ OTP do chủ đưa |
| UC-09 | Kết thúc đơn | Xong | Job kết thúc đơn. Cọc chuyển `POST_RENTAL_HOLD`. Doanh thu vào review hold |
| UC-10/13 | Khiếu nại của renter, phân xử | Xong | `RENTER_LOGIN_FAILED`, `RENTER_NOT_AS_DESCRIBED` trong complaint window. Hoàn tiền thuê, hoàn lại hoa hồng theo tỷ lệ |
| UC-11/27 | Khiếu nại thiệt hại, khấu trừ cọc | Xong | `OWNER_DAMAGE` trước `hold_until`. Khấu trừ tối đa bằng cọc của chính đơn đó |
| UC-15/36/41 | Hold doanh thu, rút tiền | Xong | Hold 72h (168h nếu seller rủi ro cao). Rút tiền khóa hàng `FOR UPDATE`. Finance đánh dấu chi thủ công |
| UC-17/33/40 | Hủy đơn | Xong | Renter hủy đúng hạn được hoàn 100%, hủy muộn hoàn 50%. Owner hủy hoàn 100% kèm tín hiệu `OWNER_LATE_CANCEL`. Cọc luôn hoàn ngay |
| UC-20 | Thông báo nhà phát hành | Một phần | Có đổi `tos_status` và đình chỉ listing. Chưa có luồng tiếp nhận và lưu hồ sơ thông báo pháp lý |
| UC-21 | Người chưa thành niên | Xong (MVP) | Chặn dưới 18 khi đăng ký và khi giao dịch, theo `min_age` của game |
| UC-22 | Hỗ trợ, audit | Một phần | Có audit, tra cứu đơn và dispute cho Support. Chưa có màn hình tra cứu audit log |
| UC-23 | Cấu hình game | Xong | `POST/PUT /admin/games`. Thay đổi chỉ áp dụng cho đơn mới |
| UC-25 | Cọc bắt buộc | Xong | Đủ máy trạng thái §6.2. Mỗi đơn một cọc, không dùng lại |
| UC-30/39 | Rotation mật khẩu | Xong | `ROTATION_PENDING` → `OVERDUE` (SLA 60'). Đơn kế tiếp bị hủy `OWNER_FAULT_NOT_READY` nếu chưa rotation |
| UC-37 | 3 chế độ kết thúc | Xong | `FORCE_AT_END`, `GRACE_FOR_CURRENT_MATCH` (+grace), `STAY_ONLINE_UNTIL_RECLAIM` → reclaim |
| UC-38 | AUTO_ACCEPT | Xong | Chặn `auto_accept` với game dùng OTP. Owner phải duyệt trong 30', quá hạn thì `REJECTED` |
| UC-42 | Dual approval | Xong | 4 loại hành động. Cấm tự duyệt ở cả service lẫn DB. Có hạn duyệt. Thực thi thất bại thì chuyển `REJECTED` |
| UC-43 | Chiếm account | Xong | Kết thúc đơn ngay, cọc `DISPUTED`, account bị treo, renter `RESTRICTED`. Khôi phục cần credential mới và dual approval |
| UC-44 | Cam kết thông tin khôi phục | Xong | Bắt buộc đủ 3 cam kết. Admin `verify-recovery` trước khi listing được gửi duyệt |

### 2.4. Kiểm thử (`src/test/java/com/rentgg`)

| File | Nội dung |
|---|---|
| `OrderLifecycleIT` (9) | Ví dụ ledger §7.4 từ đầu đến cuối, hết hạn thanh toán nhả slot, cọc thứ hai (R5), account chưa rotation, duyệt tay và quá hạn, 3 end mode, hủy đơn, fail-closed theo game, idempotency |
| `ApiIT` (5) | Cửa sổ xem credentials và rate limit, webhook sai chữ ký và gửi trùng, thanh toán muộn, xác thực và lỗi, không có endpoint staff xem mật khẩu |
| `DisputeIT` (5) | Ví dụ §7.5 (khấu trừ 120k/200k), hết hạn khiếu nại, chiếm account và khôi phục, dispute hold của renter, seller rủi ro cao |
| `MoneyAndApprovalIT` (7) | 10 người đặt cùng lúc, 5 lệnh rút song song, ledger append-only và cân bằng, dual approval, `GAME_ALLOW`, cam kết khôi phục, job chạy lặp hoặc song song |
| `UnitTest` (3) | Công thức tiền, AES-GCM (round-trip, phát hiện sửa dữ liệu, xoay khóa), HMAC |
| `ArchitectureTest` (2) | Quy tắc phụ thuộc module |
| Các file bổ sung | `AccountBusyBlockIT`, `CalendarBufferIT`, `DisputeAttachmentIT`, `ForwardedHeadersIT`, `GameAccountKeyIT`, `GameAccountPasswordIT`, `GameRiskIT`, `HelpdeskIT`, `LedgerOrderSearchIT`, `ListingEditReviewIT`, `ListingImageIT`, `ListingOwnerRiskIT`, `OrderExtensionIT`, `PaymentProvidersIT`, `RbacIT`, `RentalPackageIT`, `SettingsIT`, `UserSearchIT`, `AuthorizationCoverageTest`, `DevClockTest` (chưa ghi số ca và kết quả trong tài liệu này) |

Lệnh chạy test (máy không có Docker thì trỏ tới DB riêng):

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/rentgg_test ./mvnw test
```

### 2.5. Bổ sung so với tài liệu thiết kế

- `POST /orders/{id}/otp-note`: chủ account gửi OTP qua đơn, tự hết hạn sau 10 phút, xóa khi đơn kết thúc. Đây là kênh "ghi chú trong đơn" mà UC-08 nhắc tới.
- `GET /admin/ledger/reconciliation` (đối soát §16.3) và `GET /admin/ledger/orders/{id}`.
- `POST /admin/game-accounts/{id}/flag-suspicious`, `POST /admin/users/{id}/risk-tier`, `GET /admin/users/{id}/abuse-signals`, `GET /listings/mine`, `GET /admin/listings`, `POST /notifications/{id}/read`.
- Job `retryFailedRefunds`: thử lại refund lỗi với backoff, tối đa 10 lần.
- `POST /api/v1/dev/jobs/run-all` (chỉ profile `dev`): chạy mọi job ngay để thử bằng tay.
- `DevDataSeeder` (chỉ profile `dev`): tạo user mỗi vai trò, game mẫu, listing mẫu.
- Các phần đã có nhưng báo cáo cũ chưa ghi: RBAC và trang quản trị vai trò/cấu hình (`/admin/roles`, `/admin/settings`), helpdesk/ticket, ảnh listing, gia hạn đơn, `forward-headers-strategy: native` để đọc IP đúng khi đứng sau proxy (`application.yml`).

---

## 3. Lệch nhỏ so với thiết kế (đã cân nhắc)

| Điểm | Cách làm hiện tại | Lý do |
|---|---|---|
| Refund lỗi | Lưu `FAILED`, job riêng thử lại | Tránh tự khóa (deadlock) khi vừa giữ khóa hàng vừa ghi trạng thái lỗi trong transaction riêng |
| Tìm kiếm listing và lịch bận | Module `listing` đọc bảng `orders` bằng SQL | §3.1 yêu cầu `NOT EXISTS` trên `rental_period`. ArchUnit không bắt được SQL chéo module |
| Đối soát ledger | Đọc `deposits`, `refunds`, `orders` bằng SQL | Đối soát vốn là việc xuyên module |
| Khiếu nại của renter | Chỉ mở khi đơn `IN_RENTAL` | Đúng bảng §6.5. Nếu complaint window kéo dài qua giờ kết thúc thì phần sau giờ không khiếu nại được |
| `refunded_amount` của cọc | Ghi tại thời điểm *yêu cầu* hoàn | Để CHECK "deducted + refunded = amount" đúng ngay khi cọc vào trạng thái `SETTLED` |

---

## 4. Những gì còn thiếu

### 4.1. Còn thiếu trong phạm vi Phase 0 (nên làm trước khi beta)

| # | Hạng mục | Mức | Chi tiết |
|---|---|---|---|
| 1 | Bộ lọc che dữ liệu nhạy cảm trong log (§16.1) | Cao | Mới che qua `toString()` của DTO và không log body. Chưa có lớp lọc thứ hai cho các khóa `password/token/secret/authorization` |
| 2 | Tách quyền user DB (§5.4 ghi chú, §14.3) | Cao | Chưa tạo `rentgg_app` không có quyền TRUNCATE/DROP và `rentgg_migrator` riêng cho migration |
| 3 | Swagger trên staging/prod (§11.1) | Cao | Đang mở công khai ở mọi profile. Cần tắt hoặc bắt xác thực |
| 4 | Chạy thử Docker và Testcontainers | Cao | Dockerfile, compose và nhánh Testcontainers chưa chạy được vì máy không có Docker |
| 5 | Re-auth khi xem credentials (D5) | Trung bình | Đã có cờ `rentgg.credentials.require-reauth` nhưng chưa dùng tới |
| 6 | Rate limit đăng nhập lũy tiến (§12.5) | Trung bình | Mới có giới hạn cố định 5/phút, 20/giờ. Chưa có thời gian chờ tăng dần sau nhiều lần sai |
| 7 | Tín hiệu lạm dụng khi vượt giới hạn xem credentials (UC-08) | Thấp | Hiện chỉ ghi audit `CREDENTIAL_VIEW_RATE_LIMITED`, chưa ghi `abuse_signals` |
| 8 | Metrics còn thiếu (§16.2) | Trung bình | Chưa có `rentgg_rotation_duration_seconds`, `rentgg_rotation_overdue_total`, `rentgg_deposits_by_status` (số lượng). `rentgg_orders_transition_total` chưa phủ mọi chuyển trạng thái |
| 9 | MDC `userId` / `orderId` trong log | Thấp | Đã có `traceId` và `requestPath`. Chưa gắn user và đơn |
| 10 | Cảnh báo khi outbox kẹt hoặc refund lỗi quá số lần | Trung bình | Mới ghi log ERROR và gauge. Chưa nối Alertmanager hay rule alert |
| 11 | Endpoint quản trị còn thiếu | Trung bình | Danh sách account chờ `verify-recovery`, danh sách user, tra cứu audit log |
| 12 | Kháng nghị quyết định kiểm duyệt và dispute (UC-05, UC-13) | Trung bình | Chưa có luồng kháng nghị |
| 13 | Hồ sơ thông báo pháp lý từ nhà phát hành (UC-20) | Trung bình | Chưa có bảng hoặc luồng tiếp nhận, hạn phản hồi |
| 14 | Hạn phân xử dispute (`deadline_at`) | Thấp | Đã lưu hạn nhưng chưa có cảnh báo khi quá hạn |
| 15 | Giới hạn body 1 MB (§12.10) | Thấp | Mới cấu hình phía Tomcat. Chưa kiểm tra riêng cho JSON body |
| 16 | Chuẩn hóa thông báo (notification) | Thấp | Tiêu đề và nội dung còn đơn giản, chưa có template theo sự kiện |
| 17 | Kiểm soát phiên bản mã nguồn | Cao | Chưa khởi tạo git. Chưa có CI (build, test, test kiến trúc, quét phụ thuộc theo §17.5) |

### 4.2. Phase 1 (theo lộ trình §19.1)

- Đưa PSP thật vào vận hành: adapter MoMo/PayOS/VNPay/ZaloPay đã có code; cần cấu hình sandbox, kiểm webhook và hoàn tiền thật. Phụ thuộc quyết định D1.
- MFA (TOTP) bắt buộc cho SUPPORT, ADMIN, FINANCE.
- Chấm điểm uy tín và tái phạm đầy đủ (UC-14). Hiện mới ghi `abuse_signals`.
- Merchant/shop, tier (UC-16, UC-31). Coupon (UC-32). Chat.
- ~~Gia hạn đơn (UC-17 phần P1)~~ **Đã làm** (V14, `OrderExtensionService`, test `OrderExtensionIT`): người thuê gia hạn ngay trong trang đơn khi đơn `IN_RENTAL` và chưa tới `scheduled_end_at`. Phần giờ thêm được giữ chỗ ngay (`orders.extension_hold_until`, nằm trong `rental_period` nên exclusion constraint chặn trùng lịch), chỉ có hiệu lực khi thanh toán xong; không thu cọc lại, không cần chủ duyệt. Hạn thanh toán = min(now + `payment-hold-minutes`, `block_end_at`); quá hạn thì job `expireExtensionPayments` trả lại giờ, trả tiền muộn thì hoàn 100%. Kiểm tra: listing còn ACTIVE, game ALLOWED, account vẫn IN_USE, người thuê đủ điều kiện, tổng thời lượng ≤ `max_duration_minutes` của game, không chạm lịch kế tiếp (tính cả ân hạn theo `end_mode`). Hoàn tiền không chỉ định intent được chia theo từng payment (không hoàn quá số đã trả của một payment).
- Upload bằng chứng lên S3 (hiện chỉ nhận URL `https://`).
- ~~Frontend web~~ **Đã có** (xem `RentGG_Frontend_Status.md`).
- Job đối soát tự động (hiện chỉ có endpoint chạy khi cần) và OpenTelemetry tracing.
- ~~Ảnh listing~~ **Đã có** (V9). Chứng minh chất lượng listing (UC-35) và phát hiện cheat theo báo cáo (UC-12) chưa làm.

### 4.3. Phase 2

Quỹ bồi hoàn listing (UC-26), gói bảo vệ account (UC-18, UC-29, cần đối tác hợp lệ), đa kênh (UC-19, UC-34), Kafka, OpenSearch, Redis (rate limit toàn cục), Vault/KMS (envelope encryption, schema đã sẵn `key_id`), Kubernetes, app mobile.

### 4.4. Việc nghiệp vụ ngoài code (Validation Checklist)

Các mục dưới đây quyết định cấu hình, không cần đổi code:

- [ ] Điền bảng A cho 1–3 game ra mắt (ToS, OTP, đổi mật khẩu có đá phiên không, thời lượng, tuổi), rồi nhập qua `POST /api/v1/admin/games`.
- [ ] Chọn PSP và làm rõ mô hình thu hộ, chi hộ, hoàn tiền một phần, webhook (phần B, quyết định D1).
- [ ] Chốt mức cọc min/max và thời gian giữ cọc, giữ doanh thu theo từng game (D2, D4). Hiện đang dùng mặc định 72h.
- [ ] Phỏng vấn 10–20 người dùng (phần C). Chọn 5–10 chủ account cho beta kín.
- [ ] Quyết định có bắt re-auth khi xem credentials (D5) không.
- [x] KYC (D6): đã quyết định không bắt buộc KYC cho cả chủ account lẫn người thuê (2026-10-26).
- [ ] Thẩm định pháp lý về giữ hộ tiền (cọc, doanh thu) trước khi mở giao dịch thật.

---

## 5. Thông tin vận hành

- Chạy dev theo `CLAUDE.md` của dự án: backend cổng `8080` (profile `dev`), frontend cổng `5273`, PostgreSQL portable cổng `5432` (DB `rentgg`). Không dùng container `db` của compose.
- Công cụ cục bộ (Maven 3.9.9 và PostgreSQL 16 dạng portable) nằm ở `%LOCALAPPDATA%\rentgg-tools`. Có sẵn DB `rentgg` và `rentgg_test`, user `rentgg`.
- Chi tiết cách chạy và cấu trúc: xem `README.md`.

---

## 6. Khả năng mở rộng chưa làm

Các mục dưới đây chưa có trong code. Không phải việc bắt buộc cho beta; sắp xếp theo thứ tự thường cần đến.

| Hướng | Hiện trạng | Khi nào cần |
|---|---|---|
| Nhiều instance backend | `RateLimiter` và cache quyền (`AuthorityService`) lưu trong bộ nhớ từng máy. ShedLock đã bảo đảm job chạy một lần | Khi chạy từ 2 instance: chuyển rate limit sang Redis |
| Lưu file (ảnh, bằng chứng khiếu nại, đính kèm helpdesk) | Lưu `bytea` trong PostgreSQL (V2, V9, V13, V28) | Khi DB và backup phình to: chuyển sang S3/R2, DB chỉ giữ khóa đối tượng |
| Quan sát hệ thống | Metrics còn thiếu, chưa có tracing (OpenTelemetry), chưa nối Alertmanager | Trước production (mục 4.1 #8, #10) |
| Xác thực hai lớp cho staff | Chưa có MFA (TOTP) cho SUPPORT, ADMIN, FINANCE | Trước khi cấp nhiều tài khoản staff |
| Uy tín và đánh giá chủ account | Chỉ có `abuse_signals`, chưa có điểm, đánh giá, số lượt thuê (UC-14) | Trước khi mở rộng số chủ account |
| Kháng nghị và hồ sơ pháp lý | Chưa có (UC-05, UC-13, UC-20) | UC-20 cần trước khi có tranh chấp với nhà phát hành |
| Thông báo thời gian thực | Chuông và số chưa đọc phải tải lại trang | Khi có nhiều người dùng: SSE hoặc WebSocket |
| Đa PSP và đối soát tự động | Có adapter nhưng chưa chọn/cấu hình; đối soát chỉ chạy bằng endpoint khi cần | Khi chạy thật nhiều kênh thu tiền: chọn PSP theo game, job đối soát định kỳ |
| Tính năng thương mại | Chat, coupon, shop/tier (UC-16, UC-31, UC-32) | Theo lộ trình Phase 1 (§19.1) |
| Đa ngôn ngữ, trợ năng | Chỉ tiếng Việt, chuỗi viết trực tiếp trong code; chưa kiểm a11y | Khi mở thị trường khác |
| Hạ tầng Phase 2 | Kafka, OpenSearch, Redis toàn cục, Vault/KMS (schema đã có `key_id`), Kubernetes | Khi vượt một instance hoặc tìm kiếm chậm |
| Mobile | iOS chưa build bằng Xcode; Android chưa kiểm chứng build | Trước khi phát hành app |

## 7. Nhật ký rà soát

- **2026-10-29** — Đối chiếu tài liệu với code. Sửa: số migration (7 → 29), số lượng test, trạng thái PSP (đã có adapter), cổng dev theo `CLAUDE.md`, tình trạng Docker/CI, thêm các phần đã làm nhưng chưa được ghi.
- Phát hiện đã có sẵn `forward-headers-strategy: native` và các màn Android (mục iOS). Báo cáo trước chưa ghi nhận.
- Đã sửa cùng ngày: form khiếu nại trên web nay khớp quy tắc backend (`RentGG_Frontend_Status.md` mục 4.2 #10). Cập nhật theo đó mục 3: quy tắc "chỉ mở khi `IN_RENTAL`" áp dụng cho người thuê trên cả backend lẫn web.
- Vẫn chưa làm: các việc còn lại ở mục 4 và mục 6.
- Chưa chạy lại test trong lần rà soát này. Số liệu test mới cần `mvn test`.
