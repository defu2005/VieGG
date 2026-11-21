# Kiến trúc

Mô tả kiến trúc theo code hiện có. Ngày kiểm tra **2026-11-21**. Trạng thái ghi theo nhãn: IMPLEMENTED, PARTIAL, PLANNED, UNKNOWN.

Danh sách module và phụ thuộc: [MODULES.md](MODULES.md). Schema: [DATABASE.md](DATABASE.md). Endpoint: [API.md](API.md).

## Tổng quan

| Thành phần | Công nghệ | Trạng thái |
|---|---|---|
| Backend | Spring Boot 3.3.5, Java 21, Maven wrapper (`backend/mvnw`) | IMPLEMENTED |
| Kiến trúc backend | Modular monolith, một process, mỗi module có `api` / `application` / `domain` / `infrastructure` | IMPLEMENTED |
| Cơ sở dữ liệu | PostgreSQL 16, Flyway V1–V37 | IMPLEMENTED |
| Web | React 18.3, Vite 5.4, react-router-dom 6 (`frontend/`) | IMPLEMENTED |
| Android | Kotlin, Compose (`android/`) | PARTIAL (xem `android/README.md`) |
| iOS | SwiftUI, RentGGCore (`ios/`) | PARTIAL (xem `ios/README.md`) |
| Thanh toán | Adapter cho MoMo, PayOS, VNPay, ZaloPay; gateway giả cho dev | PARTIAL: chưa xác nhận với sandbox PSP |
| Thông báo email, push | Không có dependency trong `backend/pom.xml` | PLANNED |

Sơ đồ luồng chính (một process backend, một database):

```text
Web (Vite / Caddy) ─┐
Android / iOS ──────┼──► REST /api/v1 ──► Spring Security (JWT)
PSP (webhook) ──────┘                         │
                                   controller (api) ──► service (application)
                                                          │         │
                                              repository (infrastructure)  outbox_events
                                                          │                    │
                                                    PostgreSQL     job publishOutbox (10 giây)
                                                                               │
                                                              ApplicationEvent ──► listener trong module
```

## Phân tầng và kiểm tra tự động

- Mỗi module tách `api` / `application` / `domain` / `infrastructure` (xem [MODULES.md](MODULES.md)).
- `ArchitectureTest` (ArchUnit) kiểm tra hai quy tắc: tầng thấp không phụ thuộc tầng cao, và không truy cập repository của module khác. Đây là kiểm tra chạy trong `mvn test`.
- Khi tầng thấp cần phản ứng với tầng cao, dùng **port ngược**: interface khai báo ở tầng thấp, class tầng cao cài đặt. Ví dụ đã có trong code:
  - `payment/application/PaymentCapturedHandler`, cài đặt bởi `order/application/OrderPaymentHandler`;
  - `order/application/OrderLifecycleListener`;
  - `notification/application/StaffDirectory`.

## Bảo mật

Đọc `shared/security/SecurityConfig.java` để có danh sách chính xác.

- Stateless: không dùng session. CSRF tắt. Xác thực bằng bearer JWT.
- Refresh token được lưu trong bảng `refresh_tokens` (IMPLEMENTED).
- Endpoint công khai gồm đăng ký, đăng nhập, refresh, logout; catalog game và tìm kiếm listing; webhook và return URL của PSP; Swagger UI và OpenAPI khi bật; `/actuator/health`, `/actuator/info`, `/actuator/prometheus`. Mọi `/api/v1/**` còn lại yêu cầu đăng nhập.
- Phân quyền theo `@PreAuthorize`. Test `AuthorizationCoverageTest` bắt lỗi nếu controller nào thiếu annotation.
- Credentials của account game được mã hóa `AES/GCM/NoPadding` (`shared/crypto/CredentialCipher.java`). Khóa dùng để mã hóa mới được chọn theo `RENTGG_CREDENTIAL_ACTIVE_KEY_ID`. Bảng `credential_versions` lưu các phiên bản; phiên bản đang dùng có `status = 'ACTIVE'`.
- Tạo và xoay vòng credential được ghi audit (`CREDENTIAL_CREATED`, `CREDENTIAL_ROTATED`). Việc ghi audit mỗi lần xem credential: UNKNOWN, chưa thấy trong code.
- Giới hạn tốc độ bằng Bucket4j (`shared/ratelimit`); chống brute force đăng nhập có test `LoginBackoffIT`.
- Giới hạn kích thước body JSON bởi `JsonBodyLimitFilter` (`shared/web`).
- Log có trace id (`TraceIdFilter`, `RequestLogContext`), xuất JSON bằng logstash encoder.
- Dual approval cho thao tác nhạy cảm, module `approval`.

## Outbox và sự kiện

- Sự kiện nghiệp vụ được ghi vào bảng `outbox_events` bởi `OutboxService.record`. Method này là `@Transactional(propagation = MANDATORY)`, nên chỉ chạy trong transaction của thao tác nghiệp vụ và được commit cùng dữ liệu.
- Job `publishOutbox` (`jobs/JobScheduler.java`) chạy mỗi 10 giây (`fixedDelay = PT10S`) và phát các sự kiện bằng Spring `ApplicationEvent`. Số lần thử tối đa là `rentgg.outbox.max-attempts` (mặc định 10).
- Các listener nằm trong module nhận sự kiện. Không có message broker bên ngoài.
- Trạng thái: IMPLEMENTED. Đây là giao tiếp trong process, không phải giữa các service.

## Job định kỳ

- `jobs/JobScheduler.java` có 13 method `@Scheduled` (kiểm tra 2026-11-21 bằng `grep -c "@Scheduled" backend/src/main/java/com/rentgg/jobs/JobScheduler.java`). Mỗi method có `@SchedulerLock` (ShedLock, bảng `shedlock`) để một instance chạy một lần.
- 12 method dùng cron `${rentgg.jobs.cron}` (mặc định trong `application.yml`: `0 * * * * *`, tức mỗi phút). `publishOutbox` dùng `fixedDelay = PT10S`. Kiểm tra: `grep -c 'cron = "${rentgg.jobs.cron}"' backend/src/main/java/com/rentgg/jobs/JobScheduler.java`.
- Logic nằm trong `jobs/Jobs.java`.

## Thời gian nghiệp vụ (`Clock`)

- Code nghiệp vụ đọc thời gian qua bean `Clock` (`config/CoreConfig.java`). Có một ngoại lệ chủ ý: `PaymentService` dùng `Instant.now()` cho thời hạn checkout của PSP, vì PSP chạy theo giờ thật (có ghi chú trong code). Kiểm tra: `grep -rn "Instant.now()" backend/src/main/java`.
- Test dùng `MutableClock` để đặt thời gian.
- Ở profile `dev`, `DevClock` (`jobs/devtime/`) cho phép tua thời gian để kiểm thử các luồng theo thời gian. Độ lệch lưu ở `backend/target/dev-clock-<port>.properties`. Mỗi cổng backend có đồng hồ riêng nhưng dùng chung database. Xem [DEVELOPMENT.md](DEVELOPMENT.md#tua-thời-gian).

## Thanh toán

- Interface `PaymentGateway`. Các adapter là `@Component` trong `payment/infrastructure/psp/`: `MomoGateway`, `PayosGateway`, `VnpayGateway`, `ZalopayGateway`.
- `PaymentGateways` (`payment/application/`) là registry. `available()` chỉ trả về adapter có `isConfigured`. Provider được chọn bằng `rentgg.payment.provider`. Nếu không có provider, `require(code)` ném `PAYMENT_PROVIDER_UNAVAILABLE`.
- `FakePaymentGateway` (`payment/infrastructure/`) bật khi `rentgg.payment.fake-enabled=true`. Mặc định là `false`, còn profile `dev` đặt `true`.
- `StartupSafetyCheck` (`config/`) kiểm tra khi khởi động trên staging và prod: phải chọn một provider thật trong {PAYOS, VNPAY, MOMO, ZALOPAY}, không dùng FAKE, và credential không được là giá trị placeholder.
- Trạng thái: IMPLEMENTED ở mức code và test (`PaymentProvidersIT`, `MomoWebhookIT`, `PayosWebhookIT`). PARTIAL vì chưa xác nhận với sandbox của PSP nào (xem [DECISIONS.md](DECISIONS.md)).
- Hoàn tiền thật qua PSP: UNKNOWN. Code có `RefundRepository` và job `retryFailedRefunds`, nhưng chưa có bằng chứng chạy với PSP thật.

## Media

- Mặc định ảnh và video lưu trong database.
- Khi `rentgg.media.storage=s3`, `MediaStorageConfig` tạo `S3ObjectStorage` (dùng AWS SDK v2). Test `shared/media/S3ObjectStorageTest` kiểm tra adapter này, dùng S3Proxy làm giả lập.
- Trạng thái: IMPLEMENTED (cả hai chế độ). Chạy thật với bucket của nhà cung cấp: UNKNOWN.

## Cấu hình và profile

Nguồn: `backend/src/main/resources/application.yml`. Có ba khối profile:

| Profile | Dòng trong `application.yml` (kiểm tra 2026-11-21) | Ghi chú |
|---|---|---|
| (mặc định) | đầu file | Cấu hình chung. Các biến có giá trị mặc định dùng cho dev |
| `prod` | khoảng dòng 140 | Tắt `/v3/api-docs` và Swagger UI; media mặc định `s3` (`RENTGG_MEDIA_STORAGE`); FAKE payment mặc định `false`; `RENTGG_STAFF_MFA_REQUIRED` phải là `true` (`StartupSafetyCheck`) |
| `staging` | khoảng dòng 157 | Tắt `/v3/api-docs` và Swagger UI (chỉ khối này). Các kiểm tra an toàn của `StartupSafetyCheck` áp dụng cho cả staging và prod |
| `dev` | khoảng dòng 174 | Bật FAKE payment, seed dữ liệu mẫu, đồng hồ tua thời gian; API docs bật theo mặc định của springdoc |

Quy tắc:

- Giá trị bí mật mặc định trong `application.yml` (khóa credential, webhook secret) chỉ dành cho dev. Đừng dùng chúng ở môi trường khác. `StartupSafetyCheck` chặn một phần trường hợp này trên staging và prod.
- Danh sách biến môi trường production nằm trong [RentGG_Production_Deploy.md](RentGG_Production_Deploy.md) và `deploy/.env.prod.example`. Tài liệu này không chép giá trị.
- API docs: `springdoc` tắt trên prod và staging, bật ở dev và test. Các test `com/rentgg/docs/*` kiểm tra điều này.

## Quan sát được

- Actuator: `health`, `info`, `prometheus`. Micrometer xuất metric. Metric nghiệp vụ nằm trong `jobs/BusinessMetrics.java`.
- Log: logstash JSON. Trace id đi kèm mỗi request.

## Chưa có trong code (PLANNED hoặc không có)

- Redis, Kafka, OpenSearch, Vault, Kubernetes: không có trong `pom.xml` hay compose. Lý do chọn không dùng xem `docs/RentGG_System_Design_v1.2.md` §3.1 và [DECISIONS.md](DECISIONS.md).
- Email và push notification: PLANNED.
- Nhiều instance backend đồng thời: ShedLock đã có để tránh chạy trùng job, nhưng chưa có test nhiều instance. UNKNOWN.
