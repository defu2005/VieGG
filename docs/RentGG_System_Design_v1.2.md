# RentGG — System Design Document

**Phiên bản:** 1.2  
**Ngày:** 2026-10-26  
**Trạng thái:** Thiết kế đã chốt cho Phase 0 (MVP). Backend Spring Boot đang được xây dựng theo đúng tài liệu này.  
**Nguồn sự thật:** `RentGG_Decisions.md` (bản chốt quyết định chung cho tài liệu và backend). Nếu tài liệu này mâu thuẫn với bản chốt, bản chốt thắng và tài liệu này phải được sửa.  
**Tài liệu liên quan:** `RentGG_Use_Cases_China_Model_v8.md` (đặc tả Use Case, tham chiếu ID UC), `RentGG_Validation_Checklist.md` (checklist kiểm chứng trước ra mắt, nguồn số liệu cho cấu hình `Game`).  
**Quy ước ngôn ngữ:** tài liệu viết tiếng Việt; tên bảng, cột, enum, endpoint, class viết tiếng Anh, giữ nguyên như trong code.

---

## Mục lục

1. Thông tin phiên bản và thay đổi so với v1.1
2. Mục tiêu, non-goals, giả định
3. Tech stack
4. Kiến trúc tổng thể
5. Mô hình dữ liệu
6. Máy trạng thái
7. Ledger kế toán kép
8. Các luồng kỹ thuật then chốt
9. Jobs định kỳ
10. Outbox và sự kiện
11. API
12. Bảo mật
13. Threat model
14. Cấu hình
15. Nhất quán dữ liệu, transaction, concurrency
16. Observability
17. Triển khai
18. Chiến lược kiểm thử
19. Lộ trình, rủi ro, quyết định còn mở

---

## 1. Thông tin phiên bản và thay đổi so với v1.1

| Mục | Giá trị |
|---|---|
| Phiên bản tài liệu | 1.2 |
| Ngày | 2026-10-26 |
| Phạm vi | Phase 0 (MVP) đầy đủ; định hướng Phase 1/2 |
| Đặc tả Use Case tương ứng | v8 (UC-01 … UC-44; UC-28 đã loại bỏ) |
| Hợp đồng quyết định | `RentGG_Decisions.md` ngày 2026-10-26 |
| Trạng thái triển khai | Backend MVP (Java 21 + Spring Boot 3.3.x) đang được xây theo hợp đồng |

### 1.1. Thay đổi so với v1.1

| # | Thay đổi | v1.1 | v1.2 |
|---|---|---|---|
| 1 | **Bỏ hoàn toàn client/launcher** (R1) | Đã bỏ client nhưng vẫn mô tả Secrets Manager, `AccessCredential` theo order | Không có client. Credentials do chủ account nhập khi tạo `GameAccount`, mã hóa AES-256-GCM trong `credential_versions`; renter của đơn xem trực tiếp trong cửa sổ `[start_at, block_end_at]`. UC-28 bị loại khỏi phạm vi. |
| 2 | **Cọc bắt buộc** (R3) | `deposit_enabled` tùy chọn, cọc để Phase 1 | Mọi listing có `deposit_amount > 0` nằm trong `[deposit_min, deposit_max]` của game. Renter trả `rent + deposit` trong cùng một lần thanh toán. UC-25/UC-27 thành P0. |
| 3 | **Giữ cọc sau thuê** (R4) | Không có | Sau khi đơn `ENDED`, cọc ở trạng thái `POST_RENTAL_HOLD` thêm `deposit_hold_hours` (mặc định 72 giờ). Chủ có thể mở `OWNER_DAMAGE` trong thời gian này. Hết hạn mà không có khiếu nại thì job tự hoàn cọc. |
| 4 | **Không dùng lại cọc** (R5) | Không đề cập | Cọc gắn 1–1 với order (`deposits.order_id UNIQUE`). Không có số dư cọc dùng chung, không có API chuyển/dùng lại cọc, không bù chéo giữa các đơn. Renter đang bị giữ cọc đơn A mà thuê đơn B thì phải trả cọc mới cho B. |
| 5 | **Chủ account bắt buộc giữ thông tin khôi phục** (R2) | Không có | Khi tạo `GameAccount`, chủ cam kết (attest) đang giữ email, SĐT và 2FA khôi phục; admin xác minh (`verify-recovery`) trước khi account được dùng cho listing. Renter không bao giờ được cấp các thông tin này. UC-44 mới. |
| 6 | **GameAccount là đơn vị khóa** | Listing là đơn vị khóa lịch và rotation | Khóa lịch (exclusion constraint), trạng thái bảo mật (`security_state`) và rotation đều gắn với `GameAccount`. Mỗi `GameAccount` tối đa 1 listing `ACTIVE`. |
| 7 | **Rotation thủ công vào Phase 0** | Rotation để Phase 1 | Đổi mật khẩu là cơ chế kết thúc phiên duy nhất. Chủ gọi `POST /game-accounts/{id}/rotation` để nộp mật khẩu mới; SLA 60 phút, quá hạn thành `ROTATION_OVERDUE`. Đơn kế tiếp đến giờ mà account chưa `AVAILABLE` thì bị hủy, hoàn 100%. |
| 8 | **Ledger kế toán kép** | `LedgerEntry` một dòng có `direction` | `LedgerAccount` / `LedgerTransaction` / `LedgerPosting`; mỗi transaction tổng posting = 0, kiểm tra trong code và bằng constraint trigger deferred; trigger chặn UPDATE/DELETE. |
| 9 | **Stack gọn** | Redis, Kafka, OpenSearch, Vault, Quartz/JobRunr, K8s | PostgreSQL 16 + Flyway, `@Scheduled` + ShedLock, Outbox → Spring `ApplicationEvent`, Bucket4j in-memory, Docker Compose. Redis/Kafka/OpenSearch/Vault chuyển sang Phase 2. |
| 10 | **Chiếm account (UC-43)** | Không có | Dispute `ACCOUNT_TAKEOVER`: kết thúc đơn ngay, cọc `DISPUTED`, account `SUSPENDED_SECURITY_REVIEW`, renter `RESTRICTED`. |
| 11 | **Đã có backend MVP** | Chỉ là đề xuất | Tài liệu này là đặc tả triển khai: tên bảng, enum, endpoint, job khớp với code. |
| 12 | **Tên trạng thái chuẩn** | `PAID`, `READY_TO_START`, `IN_GRACE_PERIOD`, `CANCELLED_BY_*` | `PENDING_PAYMENT`, `AWAITING_OWNER_APPROVAL`, `CONFIRMED`, `IN_RENTAL`, `ENDED`, `CANCELLED` (+ `cancel_reason`), `EXPIRED`, `REJECTED`. Chế độ hết giờ: `FORCE_AT_END`, `GRACE_FOR_CURRENT_MATCH`, `STAY_ONLINE_UNTIL_RECLAIM`. |

Những nội dung của v1.1 còn đúng và được giữ lại: modular monolith, ledger bất biến, hold/payout có kiểm soát, dispute dựa trên bằng chứng, human override có audit và dual approval, fail-closed với ToS game, không vượt qua MFA/OTP của nhà phát hành.

---

## 2. Mục tiêu, non-goals, giả định

### 2.1. Mục tiêu

1. Hỗ trợ đầy đủ các Use Case P0 của đặc tả v8 trong một backend duy nhất, triển khai được bằng Docker Compose.
2. **Không bao giờ cho thuê trùng**: một `GameAccount` không thể có hai đơn chồng lấn thời gian, bảo đảm ở tầng cơ sở dữ liệu chứ không chỉ ở code.
3. **Tách bạch dòng tiền**: tiền thuê, cọc, doanh thu seller, phí nền tảng, hoàn tiền và chi trả nằm ở các tài khoản ledger riêng; mọi số dư tính được từ ledger bất biến.
4. **Bảo vệ chủ account**: cọc bắt buộc, giữ cọc sau thuê, chủ giữ thông tin khôi phục, rotation bắt buộc sau mỗi đơn, quy trình khẩn cấp khi bị chiếm account.
5. **Bảo vệ renter**: complaint window sau lần xem credentials đầu tiên, hoàn tiền khi chủ không sẵn sàng hoặc đưa sai thông tin, cọc luôn được hoàn hoặc khấu trừ có lý do.
6. **Fail-closed**: game không `ALLOWED`, account không `AVAILABLE`, người dùng bị hạn chế → hệ thống từ chối thay vì cho qua.
7. **Mọi tự động hóa có điểm dừng và human override có kiểm soát** (UC-42): thao tác nhạy cảm đi qua dual approval, mọi thao tác của nhân viên đều có audit.
8. **Đơn giản để vận hành**: ít thành phần hạ tầng, dễ kiểm thử với Testcontainers.

### 2.2. Non-goals

- Không xây client/launcher, không chống cheat phía máy người dùng, không chụp màn hình (UC-28 đã loại bỏ).
- Không tự động đổi mật khẩu thay chủ account, không đăng nhập hộ vào hệ thống của nhà phát hành, không vượt qua MFA/OTP/email/SĐT khôi phục.
- Không phát hành ví điện tử, không nạp tiền trước, không có số dư dùng chung cho renter; renter thanh toán từng đơn qua PSP.
- Không chi trả tự động cho seller ở MVP (Finance chi thủ công theo lô).
- Không có quỹ bồi hoàn listing (UC-26), gói bảo vệ account (UC-29), coupon, merchant tier, gia hạn đơn, chat ở MVP.
- Không sao chép nguyên trạng tham số tiền/phí của nền tảng Trung Quốc.
- Không có endpoint nào cho nhân viên (Support/Admin/Finance) xem mật khẩu.

### 2.3. Giả định

| # | Giả định | Hệ quả thiết kế |
|---|---|---|
| A1 | Phí nền tảng là **hoa hồng trừ từ tiền thuê** (`platform_fee_bps`, mặc định 1000 = 10%). Renter trả `rent_amount + deposit_amount`, không trả thêm phí. | `platform_fee = rent_amount * platform_fee_bps / 10000`, `seller_net = rent_amount - platform_fee`; ghi khi đơn kết thúc. |
| A2 | Chi trả cho seller là **thủ công**: Finance chuyển khoản theo lô ngoài hệ thống rồi đánh dấu `PAID`/`FAILED`. | Withdrawal chỉ có `REQUESTED → PAID/FAILED`; ledger có tài khoản `SELLER_PAYOUT_PROCESSING`. |
| A3 | PSP được trừu tượng hóa qua interface `PaymentGateway` (`createCheckout`, `refund`, `verifyWebhook`). PSP thật (VNPay/PayOS…) là quyết định còn mở, tích hợp ở Phase 1. | Profile `dev` dùng `FakePaymentGateway` + endpoint `POST /dev/payments/{intentId}/simulate-success`; refund của fake gateway thành công ngay. |
| A4 | Tiền là VND, lưu `bigint` (Java `long`), không có số lẻ. | Mọi phép chia làm tròn xuống (floor); phần dư thuộc về seller (vì `seller_net = rent - fee`). |
| A5 | Thời gian lưu `timestamptz`, xử lý UTC, hiển thị `Asia/Ho_Chi_Minh`. Mọi logic thời gian dùng `java.time.Clock` bean. | Test có thể "tua" thời gian bằng `MutableClock`. |
| A6 | Một instance đủ cho MVP; có thể chạy 2+ instance sau load balancer. | Job dùng ShedLock; rate limit in-memory chấp nhận sai số giữa các instance ở MVP. |
| A7 | Bằng chứng dispute ở MVP là URL do người dùng cung cấp (`evidence_urls jsonb`). | Upload lên S3 là Phase 1. |
| A8 | KYC ở MVP do admin duyệt thủ công (`POST /me/kyc` → `POST /admin/users/{id}/kyc`). | `kyc_status` là điều kiện cho đăng listing và rút tiền. |
| A9 | Game có `password_change_kicks_sessions = true` thì đổi mật khẩu sẽ đá mọi phiên; nếu `false`, rủi ro renter còn trong game sau khi kết thúc cao hơn và phải được cân nhắc khi duyệt game. | Trường này lấy từ kết quả `RentGG_Validation_Checklist.md`. |

---

## 3. Tech stack

| Thành phần | Công nghệ | Lý do chọn | Khi nào nâng cấp |
|---|---|---|---|
| Ngôn ngữ / runtime | Java 21 (LTS) | Virtual threads sẵn có, records, pattern matching; đội quen Java | — |
| Framework | Spring Boot 3.3.x, Maven | Hệ sinh thái lớn, transaction khai báo, tích hợp sẵn security/JPA/actuator | Lên 3.4+/4.x theo lịch LTS |
| Web / validation | Spring Web (MVC), Bean Validation | REST đồng bộ đủ cho tải MVP; validate DTO khai báo | — |
| Xác thực | Spring Security `oauth2-resource-server`, JWT HS256 qua Nimbus | Không cần IdP riêng; access token 15 phút, refresh token 14 ngày (lưu hash) | Phase 1: MFA cho staff; Phase 2: IdP/OIDC hoặc RS256 với JWKS khi có nhiều service |
| Truy cập dữ liệu | Spring Data JPA (Hibernate) | `@Version` optimistic locking, `PESSIMISTIC_WRITE` cho rút tiền | Bổ sung jOOQ/native query cho báo cáo nếu cần |
| Cơ sở dữ liệu | PostgreSQL 16 + extension `btree_gist` | ACID, `tstzrange` + exclusion constraint chống đặt trùng, `jsonb`, trigger | Read replica cho báo cáo khi tải đọc tăng |
| Migration | Flyway | Migration có version, chạy khi khởi động | — |
| Lập lịch | Spring `@Scheduled` + ShedLock (JDBC provider, bảng `shedlock`) | Không cần hạ tầng thêm; ShedLock bảo đảm mỗi job chỉ chạy trên 1 instance | Quartz/JobRunr nếu cần lịch động hoặc retry phức tạp |
| Sự kiện | Bảng `outbox_events` + job `publishOutbox` → Spring `ApplicationEvent` trong process | Nhất quán với transaction DB, không mất sự kiện, không cần broker | Phase 2: Kafka (outbox relay đẩy ra topic) |
| Mã hóa credentials | AES-256-GCM, IV 12 byte ngẫu nhiên, có `key_id` (envelope-ready) | Mã hóa có xác thực; `key_id` cho phép xoay khóa | Phase 2: KMS/Vault (envelope encryption: DEK theo bản ghi, KEK trong KMS) |
| Rate limit | Bucket4j in-memory | Đơn giản, đủ cho login và xem credentials | Phase 2: Bucket4j + Redis khi nhiều instance |
| API doc | springdoc-openapi (Swagger UI) | Tài liệu sống, sinh từ code | — |
| Metrics | Micrometer + Prometheus endpoint (`/actuator/prometheus`) | Chuẩn de facto, gắn Grafana dễ | Thêm OpenTelemetry tracing ở Phase 1 |
| Log | Logback JSON (logstash encoder) | Log có cấu trúc, gắn `traceId/orderId/userId` | Loki/ELK khi có nhiều instance |
| Test | JUnit 5, Spring Boot Test, Testcontainers PostgreSQL (fallback `TEST_DB_URL` khi không có Docker) | Test trên Postgres thật vì logic dựa vào exclusion constraint và trigger | — |
| Đóng gói / chạy | Docker, Docker Compose (Postgres + app) | Một lệnh để chạy đủ hệ thống | Phase 2: Kubernetes khi có nhiều service |

### 3.1. Vì sao không dùng Redis, Kafka, OpenSearch, Vault, K8s ở MVP

| Công nghệ | Vai trò trong v1.1 | Lý do bỏ ở MVP | Điều kiện đưa vào (Phase 2) |
|---|---|---|---|
| Redis | Lock giữ chỗ, rate limit, cache | Exclusion constraint của PostgreSQL đã chống đặt trùng một cách tuyệt đối và trong cùng transaction; lock Redis chỉ thêm một nguồn sự thật thứ hai có thể lệch. Rate limit in-memory đủ cho 1–2 instance. | Nhiều instance cần rate limit chính xác toàn cục; cache trang tìm kiếm khi tải đọc lớn |
| Kafka | Event bus | Toàn bộ consumer nằm trong cùng process; outbox + `ApplicationEvent` đủ đảm bảo at-least-once. Kafka thêm vận hành (cluster, schema, DLQ) không tương xứng với tải MVP. | Tách service, cần nhiều consumer độc lập hoặc đồng bộ đa kênh |
| OpenSearch | Tìm kiếm listing | Số listing MVP nhỏ; lọc theo game, giá, khoảng thời gian trống làm được bằng SQL có index (`NOT EXISTS` trên `rental_period &&`). | Full-text, ranking, facet trên hàng chục nghìn listing |
| Vault / KMS | Lưu credentials | AES-256-GCM với khóa nằm ngoài DB (biến môi trường) và `key_id` đã tách khóa khỏi dữ liệu; thiết kế envelope-ready nên chuyển sang KMS không cần đổi schema. | Yêu cầu tuân thủ, nhiều môi trường, xoay khóa tự động |
| Kubernetes | Điều phối container | 1 app + 1 Postgres chạy tốt bằng Compose. | Nhiều service, autoscaling |

---

## 4. Kiến trúc tổng thể

### 4.1. Sơ đồ ngữ cảnh

```mermaid
flowchart LR
    subgraph Users["Nguoi dung"]
        R["Renter"]
        O["Chu account (Owner)"]
        S["Staff: Support / Admin / Finance"]
    end
    FE["Web / App (quyet dinh sau)"]
    subgraph App["RentGG Backend - Spring Boot modular monolith"]
        API["REST API /api/v1 + Swagger UI"]
        JOBS["Scheduled jobs + ShedLock"]
        OUTBOX["Outbox publisher"]
    end
    DB[("PostgreSQL 16")]
    PSP["PSP qua PaymentGateway (dev: FakePaymentGateway)"]
    BANK["Chuyen khoan thu cong (Finance)"]
    GAME["He thong nha phat hanh game"]

    R --> FE
    O --> FE
    S --> FE
    FE -->|"HTTPS + JWT"| API
    API --> DB
    JOBS --> DB
    OUTBOX --> DB
    API -->|"createCheckout / refund"| PSP
    PSP -->|"webhook HMAC-SHA256"| API
    S -.->|"chi tra theo lo"| BANK
    R -.->|"dang nhap bang credentials"| GAME
    O -.->|"doi mat khau thu cong"| GAME
```

Backend **không** gọi vào hệ thống của nhà phát hành game. Đăng nhập (renter) và đổi mật khẩu (chủ account) diễn ra trực tiếp giữa người dùng và game; RentGG chỉ lưu, cấp và ghi nhận.

### 4.2. Kiến trúc bên trong (modular monolith)

```
                         ┌────────────────────────────────────────────┐
  HTTPS (JWT Bearer) ───▶│  Spring Security filter chain               │
                         │  (JWT, role check, rate limit Bucket4j)     │
                         └───────────────────┬────────────────────────┘
                                             ▼
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │ api (Controller, DTO)       — mỗi module có package api riêng                  │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ application (Service, use case, @Transactional)                               │
 │  identity  catalog  gameaccount  listing  order  payment  deposit  wallet      │
 │  dispute   approval notification audit    ledger                               │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ domain (Entity, enum trạng thái, quy tắc chuyển trạng thái)                    │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ infrastructure (Spring Data repository, PaymentGateway impl, crypto)           │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ shared: error (ApiException, GlobalExceptionHandler) · security (JWT, roles)   │
 │         crypto (CredentialCipher AES-GCM) · outbox (OutboxEvent, publisher)    │
 │ config: Clock, ShedLock, OpenAPI, Jackson, properties                          │
 └───────────────────────────────────────────────────────────────────────────────┘
                     │ JDBC (HikariCP)                 ▲
                     ▼                                 │ @Scheduled + ShedLock
              PostgreSQL 16 ◀──────────────────────────┘ (expire/start/end/release/publishOutbox)
```

### 4.3. Danh sách module

Package gốc `com.rentgg`. Mỗi module có cấu trúc `api` / `application` / `domain` / `infrastructure`.

| Module | Trách nhiệm | Use Case (v8) | Bảng sở hữu |
|---|---|---|---|
| `identity` | Đăng ký, đăng nhập, refresh token, hồ sơ, tuổi ≥ 18, KYC (nộp + admin duyệt), trạng thái người dùng (`ACTIVE/RESTRICTED/SUSPENDED`), `risk_tier`, tín hiệu lạm dụng cơ bản | UC-01, UC-21, UC-14 (phần cơ bản) | `users`, `user_roles`, `refresh_tokens`, `kyc_submissions`, `abuse_signals` |
| `catalog` | Danh mục game, `tos_status`, tham số chính sách theo game (cọc, hold, phí, hủy, thời lượng, handover mode) | UC-03, UC-20, UC-23 | `games` |
| `gameaccount` | Tạo `GameAccount` kèm credentials + cam kết khôi phục, admin xác minh khôi phục, `security_state`, rotation thủ công, reclaim, giải mã credentials cho renter hợp lệ | UC-08, UC-30, UC-39, UC-43 (trạng thái), UC-44 | `game_accounts`, `credential_versions` |
| `listing` | Tạo/sửa/nộp duyệt/tạm dừng listing, kiểm duyệt, tìm kiếm theo game/giá/khoảng trống | UC-02, UC-04, UC-05, UC-38 (cấu hình `auto_accept`) | `listings` |
| `order` | Tạo đơn + giữ chỗ, duyệt/từ chối của owner, bắt đầu/kết thúc đơn, hủy, xem credentials, xác nhận bàn giao, yêu cầu OTP | UC-06, UC-07, UC-09, UC-17 (hủy), UC-33, UC-37, UC-38, UC-40 | `orders` |
| `payment` | `PaymentIntent`, checkout, webhook có HMAC, `Refund`, abstraction `PaymentGateway` | UC-07, UC-24 (phần PSP) | `payment_intents`, `refunds` |
| `ledger` | Tài khoản, transaction, posting; API nội bộ `post(transactionType, orderId, postings)`; tính số dư | UC-24, UC-41 (phần sổ) | `ledger_accounts`, `ledger_transactions`, `ledger_postings` |
| `deposit` | Vòng đời cọc 1–1 với order, hold sau thuê, hoàn, khấu trừ | UC-25, UC-27 | `deposits` |
| `wallet` | Số dư seller (projection từ ledger), danh sách cọc của renter, yêu cầu rút tiền, Finance đánh dấu chi trả | UC-15, UC-36, UC-41 | `withdrawals` |
| `dispute` | Mở/bổ sung bằng chứng/phân xử dispute (`OWNER_DAMAGE`, `ACCOUNT_TAKEOVER`, `RENTER_LOGIN_FAILED`, `RENTER_NOT_AS_DESCRIBED`) | UC-10, UC-11, UC-13, UC-27, UC-43 | `disputes` |
| `approval` | Dual approval: tạo yêu cầu, duyệt/từ chối, thực thi hành động | UC-42 | `approval_requests` |
| `notification` | Thông báo in-app theo sự kiện | Tất cả | `notifications` |
| `audit` | Ghi audit append-only cho thao tác nhạy cảm và mọi lần xem credentials | UC-22, UC-42 | `audit_logs` |
| `shared` | `error`, `security`, `crypto`, `outbox` | — | `outbox_events`, `shedlock` |
| `config` | Bean cấu hình (`Clock`, ShedLock, OpenAPI, properties) | — | — |

### 4.4. Quy tắc phụ thuộc giữa module

1. **Không chu trình.** Thứ tự lớp (module ở dưới không biết module ở trên):
   `shared` ← `audit`, `notification` ← `identity`, `catalog`, `ledger` ← `gameaccount`, `payment`, `deposit` ← `listing` ← `order` ← `dispute`, `wallet`, `approval`.
2. Module khác chỉ gọi **application service công khai** (interface/facade) của nhau, không truy cập repository hoặc entity của module khác. Tham chiếu chéo bằng ID (`UUID`), không dùng quan hệ JPA xuyên module.
3. Gọi đồng bộ trong cùng transaction khi cần nhất quán ngay (ví dụ `order` gọi `ledger.post(...)`, `deposit.markHeld(...)`, `gameaccount.markInUse(...)` trong cùng một `@Transactional`).
4. Tác dụng phụ không cần nhất quán ngay (thông báo, gửi refund sang PSP) đi qua **outbox** → `ApplicationEvent`.
5. Module ở tầng thấp cần phản ứng với module tầng cao thì nghe event, không gọi ngược (ví dụ `notification` nghe `OrderEnded`).
6. `ledger` chỉ phụ thuộc `shared`; mọi module khác ghi tiền **duy nhất** qua `LedgerService.post`, không tự insert posting.
7. `approval` thực thi hành động bằng cách gọi service của module đích (`catalog.changeTosStatus`, `gameaccount.restoreSecurity`, `wallet.releaseSellerHoldEarly`, `ledger.postAdjustment`) với cờ `approvedBy`, không tự sửa dữ liệu.
8. Quy tắc được kiểm tra tự động bằng test kiến trúc (ArchUnit hoặc test tương đương) trong CI.

---

## 5. Mô hình dữ liệu

Quy ước chung: khóa chính `id uuid` (sinh ở ứng dụng, UUIDv7 hoặc random), mọi bảng nghiệp vụ có `created_at`, `updated_at timestamptz`; tiền `bigint` (VND); enum lưu `text` + `CHECK` (dễ migrate hơn enum Postgres); bảng có cập nhật đồng thời dùng cột `version bigint` cho `@Version`.

### 5.1. ERD

```mermaid
erDiagram
    USERS ||--o{ GAME_ACCOUNTS : "owns"
    USERS ||--o{ ORDERS : "rents"
    USERS ||--o{ WITHDRAWALS : "requests"
    USERS ||--o{ REFRESH_TOKENS : "has"
    GAMES ||--o{ GAME_ACCOUNTS : "of"
    GAMES ||--o{ LISTINGS : "of"
    GAME_ACCOUNTS ||--|{ CREDENTIAL_VERSIONS : "has"
    GAME_ACCOUNTS ||--o{ LISTINGS : "listed as"
    GAME_ACCOUNTS ||--o{ ORDERS : "locked by"
    LISTINGS ||--o{ ORDERS : "booked via"
    ORDERS ||--|| DEPOSITS : "has exactly one"
    ORDERS ||--o{ PAYMENT_INTENTS : "paid by"
    ORDERS ||--o{ REFUNDS : "refunded by"
    ORDERS ||--o{ DISPUTES : "disputed in"
    ORDERS ||--o{ LEDGER_TRANSACTIONS : "accounted in"
    LEDGER_TRANSACTIONS ||--|{ LEDGER_POSTINGS : "contains"
    LEDGER_ACCOUNTS ||--o{ LEDGER_POSTINGS : "posted to"
    USERS ||--o{ LEDGER_ACCOUNTS : "seller accounts"
    USERS ||--o{ APPROVAL_REQUESTS : "requests or approves"
    USERS ||--o{ AUDIT_LOGS : "acts"
    USERS ||--o{ NOTIFICATIONS : "receives"

    USERS {
        uuid id PK
        text email UK
        text password_hash
        text display_name
        date date_of_birth
        text status "ACTIVE RESTRICTED SUSPENDED"
        text kyc_status "NONE PENDING VERIFIED REJECTED"
        text risk_tier "STANDARD HIGH"
    }
    GAMES {
        uuid id PK
        text code UK
        text tos_status
        text handover_mode
        bigint deposit_min
        bigint deposit_max
        int deposit_hold_hours
        int platform_fee_bps
    }
    GAME_ACCOUNTS {
        uuid id PK
        uuid owner_id FK
        uuid game_id FK
        text login_hint
        text security_state
        boolean recovery_email_owned
        boolean recovery_phone_owned
        boolean two_factor_owned
        timestamptz recovery_verified_at
        bigint version
    }
    CREDENTIAL_VERSIONS {
        uuid id PK
        uuid game_account_id FK
        int version
        bytea ciphertext
        bytea iv
        text key_id
        text status "ACTIVE RETIRED"
    }
    LISTINGS {
        uuid id PK
        uuid game_account_id FK
        uuid owner_id FK
        bigint price_per_hour
        bigint deposit_amount
        boolean auto_accept
        text end_mode
        text status
        bigint version
    }
    ORDERS {
        uuid id PK
        uuid listing_id FK
        uuid game_account_id FK
        uuid renter_id FK
        uuid owner_id FK
        text status
        timestamptz start_at
        timestamptz block_end_at
        tstzrange rental_period "generated"
        bigint rent_amount
        bigint deposit_amount
        text idempotency_key
        bigint version
    }
    DEPOSITS {
        uuid id PK
        uuid order_id FK,UK
        uuid renter_id FK
        bigint amount
        text status
        timestamptz hold_until
    }
    PAYMENT_INTENTS {
        uuid id PK
        uuid order_id FK
        text provider
        text provider_ref
        bigint amount
        text status
    }
    REFUNDS {
        uuid id PK
        uuid order_id FK
        text kind "RENT DEPOSIT"
        bigint amount
        text status
    }
    DISPUTES {
        uuid id PK
        uuid order_id FK
        uuid opened_by FK
        text type
        text status
        bigint deposit_deduction
        bigint rent_refund
    }
    WITHDRAWALS {
        uuid id PK
        uuid owner_id FK
        bigint amount
        text status
        text idempotency_key
    }
    LEDGER_ACCOUNTS {
        uuid id PK
        text code
        uuid owner_user_id FK
    }
    LEDGER_TRANSACTIONS {
        uuid id PK
        text type
        uuid order_id FK
        text reference
    }
    LEDGER_POSTINGS {
        uuid id PK
        uuid transaction_id FK
        uuid account_id FK
        uuid order_id FK
        bigint amount "debit positive"
    }
    APPROVAL_REQUESTS {
        uuid id PK
        text action_type
        jsonb payload
        uuid requested_by FK
        uuid approved_by FK
        text status
    }
    AUDIT_LOGS {
        uuid id PK
        uuid actor_id FK
        text action
        text entity_type
        uuid entity_id
    }
    NOTIFICATIONS {
        uuid id PK
        uuid user_id FK
        text type
        boolean read
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        text token_hash UK
        timestamptz expires_at
    }
```

Bảng hạ tầng không vẽ trong ERD: `outbox_events`, `shedlock`, `user_roles`, `kyc_submissions`, `abuse_signals`.

### 5.2. Bảng `games` — cấu hình chính sách theo game

Mỗi trường dưới đây tương ứng một câu hỏi trong `RentGG_Validation_Checklist.md` (phần A). Giá trị mặc định xem §14.1.

| Cột | Kiểu | Ý nghĩa |
|---|---|---|
| `code`, `name`, `publisher` | text | Định danh game (`code` unique) |
| `tos_status` | text | `ALLOWED` / `REVIEW_REQUIRED` / `PROHIBITED` / `SUSPENDED`. Chỉ `ALLOWED` mới được đăng listing, tìm thấy trong search và tạo đơn (fail-closed). Đổi sang `ALLOWED` từ `PROHIBITED`/`SUSPENDED` phải qua dual approval `GAME_ALLOW`. |
| `tos_url`, `tos_reviewed_at` | text, timestamptz | Bằng chứng rà soát ToS |
| `handover_mode` | text | `STATIC_CREDENTIAL` (username + password là đủ) hoặc `OWNER_ASSISTED_OTP` (cần chủ đưa mã OTP mỗi lần đăng nhập). `OWNER_ASSISTED_OTP` cấm `auto_accept`. |
| `password_change_kicks_sessions` | boolean | Đổi mật khẩu có đá mọi phiên đang đăng nhập không. Quyết định mức rủi ro của chế độ kết thúc phiên. |
| `deposit_min`, `deposit_max` | bigint | Khoảng cọc chủ được phép đặt; `0 < deposit_min ≤ deposit_max` |
| `deposit_hold_hours` | int | Thời gian giữ cọc sau khi đơn kết thúc (mặc định 72) |
| `seller_hold_hours`, `seller_hold_hours_high_risk` | int | Hold doanh thu seller (72 / 168 giờ cho seller `risk_tier = HIGH`) |
| `complaint_window_minutes` | int | Cửa sổ renter báo `RENTER_LOGIN_FAILED`/`RENTER_NOT_AS_DESCRIBED`, tính từ `credential_first_viewed_at` (15) |
| `min_duration_minutes`, `max_duration_minutes` | int | Giới hạn thời lượng thuê (60 / 1440) |
| `grace_minutes` | int | Ân hạn / khoảng đệm lịch cho mọi chế độ trừ `FORCE_AT_END` (10) |
| `platform_fee_bps` | int | Hoa hồng nền tảng, basis points (1000 = 10%) |
| `renter_free_cancel_minutes` | int | Renter hủy trước `start_at` ít nhất chừng này phút thì hoàn 100% tiền thuê (120) |
| `late_cancel_refund_bps` | int | Tỷ lệ hoàn tiền thuê khi renter hủy muộn (5000 = 50%) |
| `owner_free_cancel_hours` | int | Owner hủy muộn hơn mốc này trước `start_at` bị ghi `OWNER_LATE_CANCEL` (12) |
| `min_age` | int | Tuổi tối thiểu (18) |

### 5.3. Các bảng chính khác

**`users`** — `email` (unique, lowercase), `password_hash` (BCrypt), `display_name`, `date_of_birth`, `status` (`ACTIVE|RESTRICTED|SUSPENDED`), `kyc_status` (`NONE|PENDING|VERIFIED|REJECTED`), `risk_tier` (`STANDARD|HIGH`). Vai trò ở `user_roles(user_id, role)` với `role ∈ {USER, SUPPORT, ADMIN, FINANCE}`. Quy tắc: giao dịch (tạo đơn, đăng listing, rút tiền) yêu cầu tuổi ≥ `min_age` của game và tối thiểu 18; đăng listing và rút tiền yêu cầu `kyc_status = VERIFIED`; `RESTRICTED` không được tạo đơn mới; `SUSPENDED` không đăng nhập được.

**`refresh_tokens`** — `user_id`, `token_hash` (SHA-256 của token, unique), `expires_at` (14 ngày), `revoked_at`. Refresh xoay vòng: dùng một lần, cấp token mới, thu hồi token cũ; phát hiện dùng lại token đã thu hồi → thu hồi toàn bộ token của user.

**`kyc_submissions`** — `user_id`, `payload jsonb` (thông tin nộp, không lưu ảnh giấy tờ ở MVP), `status`, `reviewed_by`, `reviewed_at`.

**`abuse_signals`** — `user_id`, `type` (`OWNER_LATE_CANCEL`, `OWNER_FAULT_NOT_READY`, `OWNER_LOGIN_FAILED_CONFIRMED`, `ROTATION_OVERDUE`, `RENTER_TAKEOVER_CONFIRMED`, `FALSE_CLAIM`…), `order_id`, `created_at`. MVP chỉ ghi và hiển thị cho Support; chấm điểm đầy đủ là UC-14 Phase 1.

**`game_accounts`** — `owner_id`, `game_id`, `server_region`, `login_hint` (username đã che, ví dụ `ng***93`), `security_state`, `security_state_changed_at`, `rotation_due_at` (khi vào `ROTATION_PENDING` = now + `rotation_sla_minutes`), cam kết khôi phục `recovery_email_owned`, `recovery_phone_owned`, `two_factor_owned`, `attested_at`, xác minh `recovery_verified_by`, `recovery_verified_at`, `version`. **Đây là đơn vị khóa lịch và rotation.**

**`credential_versions`** — `game_account_id`, `version` (tăng dần), `ciphertext bytea` (username + password đã mã hóa cùng nhau dưới dạng JSON), `iv bytea` (12 byte), `key_id`, `status` (`ACTIVE|RETIRED`), `created_at`. Unique `(game_account_id, version)`; partial unique: tối đa 1 bản `ACTIVE` mỗi account.

**`listings`** — `game_account_id`, `owner_id`, `game_id`, `title`, `description`, `attributes jsonb` (rank, tướng, skin…), `price_per_hour`, `deposit_amount` (`> 0`, trong `[deposit_min, deposit_max]` của game, kiểm tra ở service vì phụ thuộc bảng khác), `auto_accept`, `end_mode` (`FORCE_AT_END|GRACE_FOR_CURRENT_MATCH|STAY_ONLINE_UNTIL_RECLAIM`), `status`, `review_note`, `version`.

**`orders`** — các cột:

| Cột | Ý nghĩa |
|---|---|
| `listing_id`, `game_account_id`, `renter_id`, `owner_id` | Liên kết; `game_account_id` sao chép từ listing để exclusion constraint dùng trực tiếp |
| `status` | `PENDING_PAYMENT`, `AWAITING_OWNER_APPROVAL`, `CONFIRMED`, `IN_RENTAL`, `ENDED`, `CANCELLED`, `EXPIRED`, `REJECTED` |
| `start_at`, `scheduled_end_at` | Khung thuê renter mua (`scheduled_end_at = start_at + duration`) |
| `block_end_at` | Mốc khóa lịch: `= scheduled_end_at + grace_minutes` nếu `GRACE_FOR_CURRENT_MATCH` hoặc `STAY_ONLINE_UNTIL_RECLAIM`, `= scheduled_end_at` nếu `FORCE_AT_END` |
| `actual_end_at` | Thời điểm đơn thực sự kết thúc (job hoặc khi mở `ACCOUNT_TAKEOVER`) |
| `rental_period` | `tstzrange(start_at, block_end_at, '[)')` GENERATED STORED |
| `rent_amount`, `platform_fee`, `seller_net`, `deposit_amount` | Số tiền chốt tại thời điểm đặt |
| `policy_snapshot jsonb` | Giá, cọc, `end_mode`, các tham số game tại thời điểm đặt (phí, hold, hủy, complaint window, grace). Mọi quyết định sau đó dùng snapshot, không dùng cấu hình hiện tại. |
| `payment_expires_at` | `created_at + 15 phút` |
| `approval_expires_at` | Khi vào `AWAITING_OWNER_APPROVAL`: `+ 30 phút` |
| `credential_first_viewed_at` | Lần xem credentials đầu tiên; mốc bắt đầu complaint window |
| `handover_confirmed_at` | Renter xác nhận đăng nhập thành công (kết thúc complaint window sớm) |
| `cancel_reason` | `RENTER_CANCELLED`, `RENTER_LATE_CANCELLED`, `OWNER_CANCELLED`, `OWNER_FAULT_NOT_READY`, `PLATFORM_GAME_SUSPENDED`, `LATE_PAYMENT`; với `REJECTED`: `OWNER_REJECTED`, `APPROVAL_TIMEOUT` |
| `seller_hold_until`, `seller_released_at` | Hold doanh thu seller của đơn này (§7.6) |
| `ending_reminder_sent_at` | Đã gửi nhắc 10 phút trước khi hết giờ |
| `idempotency_key` | Unique theo `(renter_id, idempotency_key)` |
| `version` | Optimistic locking |

**`deposits`** — `order_id` (UNIQUE — cọc gắn 1–1 với order), `renter_id`, `amount`, `status`, `hold_until`, `deducted_amount`, `refunded_amount`, `version`. Bất biến: `deducted_amount + refunded_amount ≤ amount`; khi ở trạng thái cuối (`REFUNDED`, `SETTLED`) thì `deducted_amount + refunded_amount = amount`.

**`payment_intents`** — `order_id`, `provider` (`FAKE`, sau này `VNPAY`/`PAYOS`), `provider_ref` (unique theo `(provider, provider_ref)`), `amount` (= `rent_amount + deposit_amount`), `status` (`CREATED|SUCCEEDED|FAILED|EXPIRED`), `checkout_url`, `idempotency_key`.

**`refunds`** — `order_id`, `kind` (`RENT|DEPOSIT`), `amount`, `status` (`PENDING|SUCCEEDED|FAILED`), `provider_ref`, `reason`.

**`disputes`** — `order_id`, `opened_by`, `type` (`OWNER_DAMAGE|ACCOUNT_TAKEOVER|RENTER_LOGIN_FAILED|RENTER_NOT_AS_DESCRIBED`), `status` (`OPEN|RESOLVED|REJECTED`), `description`, `evidence_urls jsonb`, `deadline_at` (hạn phân xử), kết quả `deposit_deduction`, `rent_refund`, `decided_by`, `decided_at`, `reason_code`. Partial unique: tối đa 1 dispute `OPEN` cùng `type` cho mỗi order.

**`withdrawals`** — `owner_id`, `amount`, `status` (`REQUESTED|PAID|FAILED`), `bank_snapshot jsonb` (tên ngân hàng, số tài khoản che bớt khi trả về API, chủ tài khoản), `idempotency_key` unique theo `(owner_id, idempotency_key)`, `processed_by`, `processed_at`, `failure_reason`.

**`ledger_accounts`** — `code` (`PSP_CLEARING`, `RENT_ESCROW`, `DEPOSIT_HELD`, `REFUND_PAYABLE`, `PLATFORM_REVENUE`, `SELLER_REVIEW_HOLD`, `SELLER_DISPUTE_HOLD`, `SELLER_AVAILABLE`, `SELLER_PAYOUT_PROCESSING`), `owner_user_id` (null cho tài khoản hệ thống, = seller cho 4 tài khoản `SELLER_*`). Unique `(code, owner_user_id)` (dùng `NULLS NOT DISTINCT`).

**`ledger_transactions`** — `type` (§7.3), `order_id` (nullable), `reference` (ví dụ `withdrawal:{id}`, `dispute:{id}`), `idempotency_key` unique (chống ghi trùng khi job/webhook chạy lại), `created_by`, `created_at`.

**`ledger_postings`** — `transaction_id`, `account_id`, `order_id` (sao chép từ transaction, để tách số dư `DEPOSIT_HELD`/`RENT_ESCROW` theo đơn), `amount` (có dấu, ≠ 0), `created_at`.

**`approval_requests`** — `action_type` (`GAME_ALLOW|ACCOUNT_SECURITY_RESTORE|SELLER_HOLD_EARLY_RELEASE|LEDGER_ADJUSTMENT`), `payload jsonb`, `requested_by`, `reason_code`, `status` (`PENDING|APPROVED|REJECTED|EXECUTED`), `approved_by`, `decided_at`, `executed_at`, `expires_at`. `CHECK (approved_by IS NULL OR approved_by <> requested_by)`.

**`audit_logs`** — `actor_id`, `actor_roles`, `action` (ví dụ `CREDENTIAL_VIEWED`, `LISTING_APPROVED`, `DISPUTE_RESOLVED`, `WITHDRAWAL_MARKED_PAID`, `APPROVAL_EXECUTED`), `entity_type`, `entity_id`, `before jsonb`, `after jsonb` (không bao giờ chứa credentials), `reason_code`, `ip`, `user_agent`, `created_at`. Append-only.

**`notifications`** — `user_id`, `type`, `title`, `body`, `data jsonb`, `read_at`, `created_at`.

**`outbox_events`** — `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload jsonb`, `created_at`, `published_at`, `attempts`, `last_error`.

**`shedlock`** — bảng chuẩn của ShedLock (`name`, `lock_until`, `locked_at`, `locked_by`).

### 5.4. Constraint quan trọng (SQL minh họa)

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 5.4.1 Orders: rental_period sinh tự động + exclusion constraint chống đặt trùng theo GameAccount
CREATE TABLE orders (
    id                  uuid PRIMARY KEY,
    listing_id          uuid NOT NULL REFERENCES listings(id),
    game_account_id     uuid NOT NULL REFERENCES game_accounts(id),
    renter_id           uuid NOT NULL REFERENCES users(id),
    owner_id            uuid NOT NULL REFERENCES users(id),
    status              text NOT NULL CHECK (status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL',
                          'CONFIRMED','IN_RENTAL','ENDED','CANCELLED','EXPIRED','REJECTED')),
    start_at            timestamptz NOT NULL,
    scheduled_end_at    timestamptz NOT NULL,
    block_end_at        timestamptz NOT NULL,
    actual_end_at       timestamptz,
    rent_amount         bigint NOT NULL CHECK (rent_amount > 0),
    platform_fee        bigint NOT NULL CHECK (platform_fee >= 0),
    seller_net          bigint NOT NULL,
    deposit_amount      bigint NOT NULL CHECK (deposit_amount > 0),
    policy_snapshot     jsonb NOT NULL,
    payment_expires_at  timestamptz NOT NULL,
    approval_expires_at timestamptz,
    credential_first_viewed_at timestamptz,
    handover_confirmed_at      timestamptz,
    cancel_reason       text,
    seller_hold_until   timestamptz,
    seller_released_at  timestamptz,
    ending_reminder_sent_at timestamptz,
    idempotency_key     text NOT NULL,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    rental_period       tstzrange GENERATED ALWAYS AS (tstzrange(start_at, block_end_at, '[)')) STORED,
    CONSTRAINT ck_orders_time CHECK (start_at < scheduled_end_at AND scheduled_end_at <= block_end_at),
    CONSTRAINT ck_orders_money CHECK (seller_net = rent_amount - platform_fee),
    CONSTRAINT ux_orders_idem UNIQUE (renter_id, idempotency_key),
    CONSTRAINT ex_orders_no_overlap EXCLUDE USING gist (
        game_account_id WITH =,
        rental_period   WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL'))
);
-- Lỗi vi phạm ex_orders_no_overlap có SQLSTATE 23P01 (exclusion_violation) → API trả 409 SLOT_UNAVAILABLE.
-- Đơn ENDED/CANCELLED/EXPIRED/REJECTED tự động "nhả" khung giờ vì nằm ngoài điều kiện WHERE.

CREATE INDEX ix_orders_status_start ON orders (status, start_at);
CREATE INDEX ix_orders_status_block_end ON orders (status, block_end_at);
CREATE INDEX ix_orders_status_payment_exp ON orders (status, payment_expires_at);

-- 5.4.2 Mỗi GameAccount tối đa 1 listing ACTIVE
CREATE UNIQUE INDEX ux_listings_one_active_per_account
    ON listings (game_account_id) WHERE status = 'ACTIVE';

-- 5.4.3 Cọc gắn 1-1 với order, không có cọc dùng chung
CREATE TABLE deposits (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL UNIQUE REFERENCES orders(id),
    renter_id        uuid NOT NULL REFERENCES users(id),
    amount           bigint NOT NULL CHECK (amount > 0),
    status           text NOT NULL CHECK (status IN ('PENDING_PAYMENT','HELD','POST_RENTAL_HOLD',
                       'DISPUTED','REFUND_PENDING','REFUNDED','SETTLED','VOID')),
    hold_until       timestamptz,
    deducted_amount  bigint NOT NULL DEFAULT 0 CHECK (deducted_amount >= 0),
    refunded_amount  bigint NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_deposit_sum CHECK (deducted_amount + refunded_amount <= amount)
);
CREATE INDEX ix_deposits_status_hold ON deposits (status, hold_until);

-- 5.4.4 Idempotency
CREATE UNIQUE INDEX ux_payment_provider_ref ON payment_intents (provider, provider_ref)
    WHERE provider_ref IS NOT NULL;
CREATE UNIQUE INDEX ux_withdrawals_idem ON withdrawals (owner_id, idempotency_key);
CREATE UNIQUE INDEX ux_ledger_tx_idem ON ledger_transactions (idempotency_key);
CREATE UNIQUE INDEX ux_credential_active ON credential_versions (game_account_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_dispute_open_per_type ON disputes (order_id, type) WHERE status = 'OPEN';

-- 5.4.5 Append-only cho audit_logs, ledger_transactions, ledger_postings
CREATE OR REPLACE FUNCTION forbid_update_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only (% not allowed)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_ledger_tx_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
CREATE TRIGGER trg_ledger_postings_append_only
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();
-- Chặn cả TRUNCATE:
CREATE TRIGGER trg_ledger_postings_no_truncate
    BEFORE TRUNCATE ON ledger_postings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

-- 5.4.6 Constraint trigger deferred: tổng posting của mỗi transaction phải = 0 khi COMMIT
CREATE OR REPLACE FUNCTION check_ledger_tx_balanced() RETURNS trigger AS $$
DECLARE
    total bigint;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total
      FROM ledger_postings WHERE transaction_id = NEW.transaction_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % is unbalanced: sum = %', NEW.transaction_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_tx_balanced();

-- 5.4.7 Dual approval: người duyệt khác người yêu cầu
ALTER TABLE approval_requests
    ADD CONSTRAINT ck_approval_four_eyes CHECK (approved_by IS NULL OR approved_by <> requested_by);
```

Ghi chú:
- User DB của ứng dụng (`rentgg_app`) không có quyền `TRUNCATE`/`DROP` trên bảng ledger và audit ở môi trường prod; migration chạy bằng user riêng (`rentgg_migrator`).
- Kiểm tra cân bằng được làm hai lớp: `LedgerService.post` từ chối danh sách posting có tổng ≠ 0 trước khi ghi; constraint trigger là lưới an toàn cuối cùng.

---

## 6. Máy trạng thái

Mọi chuyển trạng thái được thực hiện trong service của module sở hữu, kiểm tra trạng thái nguồn hợp lệ (nếu không: `409 INVALID_STATE`), cập nhật có `@Version`, và ghi outbox event trong cùng transaction.

### 6.1. Order

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : renter tao don (giu cho 15 phut)
    PENDING_PAYMENT --> EXPIRED : het payment_expires_at
    PENDING_PAYMENT --> AWAITING_OWNER_APPROVAL : webhook SUCCEEDED, auto_accept = false
    PENDING_PAYMENT --> CONFIRMED : webhook SUCCEEDED, auto_accept = true
    PENDING_PAYMENT --> CANCELLED : renter huy truoc khi tra tien
    AWAITING_OWNER_APPROVAL --> CONFIRMED : owner approve
    AWAITING_OWNER_APPROVAL --> REJECTED : owner reject hoac qua 30 phut
    AWAITING_OWNER_APPROVAL --> CANCELLED : renter huy
    CONFIRMED --> IN_RENTAL : den start_at va account AVAILABLE
    CONFIRMED --> CANCELLED : den start_at ma account chua AVAILABLE
    CONFIRMED --> CANCELLED : renter hoac owner huy truoc start_at
    IN_RENTAL --> ENDED : den block_end_at
    IN_RENTAL --> ENDED : owner mo ACCOUNT_TAKEOVER
    ENDED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
    REJECTED --> [*]
```

| Từ | Sự kiện | Đến | Kích hoạt | Tác dụng kèm theo |
|---|---|---|---|---|
| — | `POST /orders` hợp lệ | `PENDING_PAYMENT` | Renter | Tạo `deposits` `PENDING_PAYMENT`; giữ chỗ bằng exclusion constraint; `payment_expires_at = now + 15'` |
| `PENDING_PAYMENT` | Quá `payment_expires_at` | `EXPIRED` | Job `expirePendingPayments` | Deposit `VOID`, intent `EXPIRED`; không có bút toán (chưa thu tiền) |
| `PENDING_PAYMENT` | Webhook `SUCCEEDED`, `auto_accept = true` | `CONFIRMED` | PSP webhook | Bút toán `PAYMENT_CAPTURED`; deposit `HELD` |
| `PENDING_PAYMENT` | Webhook `SUCCEEDED`, `auto_accept = false` | `AWAITING_OWNER_APPROVAL` | PSP webhook | `PAYMENT_CAPTURED`; deposit `HELD`; `approval_expires_at = now + 30'`; thông báo owner |
| `PENDING_PAYMENT` | `POST /orders/{id}/cancel` | `CANCELLED` (`RENTER_CANCELLED`) | Renter | Intent hủy; deposit `VOID`; không có bút toán |
| `AWAITING_OWNER_APPROVAL` | `POST /orders/{id}/approve` | `CONFIRMED` | Owner | Thông báo renter |
| `AWAITING_OWNER_APPROVAL` | `POST /orders/{id}/reject` | `REJECTED` (`OWNER_REJECTED`) | Owner | Hoàn 100% thuê + cọc (`ORDER_CANCELLED_REFUND`); deposit `REFUND_PENDING` |
| `AWAITING_OWNER_APPROVAL` | Quá `approval_expires_at` | `REJECTED` (`APPROVAL_TIMEOUT`) | Job `expireOwnerApprovals` | Như trên |
| `AWAITING_OWNER_APPROVAL` / `CONFIRMED` | Renter hủy | `CANCELLED` (`RENTER_CANCELLED` / `RENTER_LATE_CANCELLED`) | Renter | Hoàn tiền thuê theo chính sách (§8.7); cọc hoàn 100% ngay |
| `CONFIRMED` | Owner hủy | `CANCELLED` (`OWNER_CANCELLED`) | Owner | Hoàn 100%; nếu còn < `owner_free_cancel_hours` → `abuse_signals` `OWNER_LATE_CANCEL` |
| `CONFIRMED` | Đến `start_at`, account `AVAILABLE`, game `ALLOWED` | `IN_RENTAL` | Job `startDueOrders` | GameAccount `IN_USE`; credentials xem được |
| `CONFIRMED` | Đến `start_at`, account không `AVAILABLE` | `CANCELLED` (`OWNER_FAULT_NOT_READY`) | Job `startDueOrders` | Hoàn 100%; `abuse_signals` `OWNER_FAULT_NOT_READY` |
| `CONFIRMED` | Đến `start_at`, game không còn `ALLOWED` | `CANCELLED` (`PLATFORM_GAME_SUSPENDED`) | Job `startDueOrders` | Hoàn 100%, không ghi tín hiệu cho owner |
| `IN_RENTAL` | Đến `block_end_at` | `ENDED` | Job `endDueOrders` | `ORDER_ENDED`; deposit `POST_RENTAL_HOLD`; GameAccount `ROTATION_PENDING` hoặc `AWAITING_RECLAIM`; `seller_hold_until` |
| `IN_RENTAL` | Owner mở `ACCOUNT_TAKEOVER` | `ENDED` | Owner | `actual_end_at = now`; `ORDER_ENDED`; deposit `DISPUTED`; GameAccount `SUSPENDED_SECURITY_REVIEW`; renter `RESTRICTED` |

Không có chuyển trạng thái hủy từ `IN_RENTAL`: sự cố trong lúc thuê đi qua dispute.

### 6.2. Deposit

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : tao cung order
    PENDING_PAYMENT --> VOID : order EXPIRED hoac huy truoc khi tra tien
    PENDING_PAYMENT --> HELD : thanh toan thanh cong
    HELD --> REFUND_PENDING : huy hoac reject truoc khi thue
    HELD --> POST_RENTAL_HOLD : order ENDED
    HELD --> DISPUTED : ACCOUNT_TAKEOVER trong luc thue
    POST_RENTAL_HOLD --> DISPUTED : OWNER_DAMAGE hoac ACCOUNT_TAKEOVER truoc hold_until
    POST_RENTAL_HOLD --> REFUND_PENDING : het hold_until, khong co dispute
    DISPUTED --> REFUND_PENDING : phan xu khong khau tru
    DISPUTED --> SETTLED : phan xu khau tru mot phan hoac toan bo
    REFUND_PENDING --> REFUNDED : PSP hoan thanh cong
    REFUND_PENDING --> REFUND_PENDING : PSP loi, thu lai
    VOID --> [*]
    REFUNDED --> [*]
    SETTLED --> [*]
```

| Từ | Sự kiện | Đến | Kích hoạt | Bút toán |
|---|---|---|---|---|
| — | Tạo order | `PENDING_PAYMENT` | Renter | — |
| `PENDING_PAYMENT` | Order `EXPIRED` / hủy trước thanh toán | `VOID` | Job / Renter | — |
| `PENDING_PAYMENT` | Webhook thanh toán thành công | `HELD` | PSP webhook | Phần cọc trong `PAYMENT_CAPTURED` |
| `HELD` | Order `CANCELLED` / `REJECTED` trước `start_at` | `REFUND_PENDING` | Renter/Owner/Job | `ORDER_CANCELLED_REFUND` (phần cọc) — không giữ thêm |
| `HELD` | Order `ENDED` bình thường | `POST_RENTAL_HOLD` (`hold_until = actual_end_at + deposit_hold_hours`) | Job `endDueOrders` | — (chỉ đổi trạng thái) |
| `HELD` | `ACCOUNT_TAKEOVER` mở khi `IN_RENTAL` | `DISPUTED` | Owner | — |
| `POST_RENTAL_HOLD` | `OWNER_DAMAGE` hoặc `ACCOUNT_TAKEOVER` trước `hold_until` | `DISPUTED` | Owner | — |
| `POST_RENTAL_HOLD` | `now ≥ hold_until`, không có dispute nhắm vào cọc | `REFUND_PENDING` | Job `releaseDeposits` | `DEPOSIT_REFUND_REQUESTED` |
| `DISPUTED` | Resolve với `deposit_deduction = 0` hoặc dispute `REJECTED` | `REFUND_PENDING` | Support | `DEPOSIT_REFUND_REQUESTED` (toàn bộ) |
| `DISPUTED` | Resolve với `deposit_deduction = x > 0` | `SETTLED` | Support | `DEPOSIT_DEDUCTED` x; nếu `amount − x > 0` thì `DEPOSIT_REFUND_REQUESTED` (phần còn lại) + tạo `Refund` |
| `REFUND_PENDING` | Refund PSP `SUCCEEDED` | `REFUNDED` | Outbox consumer / webhook | `REFUND_COMPLETED` |

Với `SETTLED`, phần còn lại (nếu có) vẫn được hoàn qua bản ghi `refunds` (kind `DEPOSIT`); trạng thái cọc không quay lại `REFUND_PENDING` mà theo dõi qua `refunds.status`. Nếu dispute được giải quyết khi `now < hold_until` mà kết luận không khấu trừ, cọc vẫn hoàn ngay (không chờ hết hold).

### 6.3. GameAccount `security_state`

```mermaid
stateDiagram-v2
    [*] --> PENDING_VERIFICATION : owner tao account + attestation
    PENDING_VERIFICATION --> AVAILABLE : admin verify-recovery
    AVAILABLE --> IN_USE : startDueOrders
    IN_USE --> ROTATION_PENDING : ENDED, FORCE hoac GRACE
    IN_USE --> AWAITING_RECLAIM : ENDED, STAY_ONLINE
    AWAITING_RECLAIM --> ROTATION_PENDING : owner reclaim
    ROTATION_PENDING --> AVAILABLE : owner rotation
    ROTATION_PENDING --> ROTATION_OVERDUE : qua rotation_due_at
    ROTATION_OVERDUE --> AVAILABLE : owner rotation
    IN_USE --> SUSPENDED_SECURITY_REVIEW : ACCOUNT_TAKEOVER
    ROTATION_PENDING --> SUSPENDED_SECURITY_REVIEW : ACCOUNT_TAKEOVER hoac nghi ngo
    ROTATION_OVERDUE --> SUSPENDED_SECURITY_REVIEW : ACCOUNT_TAKEOVER hoac nghi ngo
    AWAITING_RECLAIM --> SUSPENDED_SECURITY_REVIEW : ACCOUNT_TAKEOVER
    AVAILABLE --> SUSPENDED_SECURITY_REVIEW : admin hoac support danh dau nghi ngo
    SUSPENDED_SECURITY_REVIEW --> AVAILABLE : dual approval ACCOUNT_SECURITY_RESTORE
```

| Từ | Sự kiện | Đến | Kích hoạt | Ghi chú |
|---|---|---|---|---|
| — | `POST /game-accounts` (credentials + 3 cam kết khôi phục = true) | `PENDING_VERIFICATION` | Owner (KYC `VERIFIED`) | Thiếu bất kỳ cam kết nào → `422 RECOVERY_ATTESTATION_REQUIRED` |
| `PENDING_VERIFICATION` | `POST /admin/game-accounts/{id}/verify-recovery` | `AVAILABLE` | Admin | Ghi `recovery_verified_by/at`, audit. Chỉ account đã xác minh mới được gắn vào listing để nộp duyệt |
| `AVAILABLE` | Đơn bắt đầu | `IN_USE` | Job `startDueOrders` | |
| `IN_USE` | Đơn `ENDED`, `end_mode ∈ {FORCE_AT_END, GRACE_FOR_CURRENT_MATCH}` | `ROTATION_PENDING` | Job `endDueOrders` | `rotation_due_at = now + rotation_sla_minutes`; thông báo owner đổi mật khẩu ngay |
| `IN_USE` | Đơn `ENDED`, `STAY_ONLINE_UNTIL_RECLAIM` | `AWAITING_RECLAIM` | Job `endDueOrders` | Renter có thể vẫn trong game; listing không nhận đơn mới |
| `AWAITING_RECLAIM` | `POST /game-accounts/{id}/reclaim` | `ROTATION_PENDING` | Owner | Đặt `rotation_due_at` |
| `ROTATION_PENDING` / `ROTATION_OVERDUE` | `POST /game-accounts/{id}/rotation` (mật khẩu mới + cam kết đã đăng xuất mọi thiết bị) | `AVAILABLE` | Owner | Tạo `CredentialVersion` mới `ACTIVE`, bản cũ `RETIRED` |
| `ROTATION_PENDING` | `now ≥ rotation_due_at` | `ROTATION_OVERDUE` | Job `markRotationOverdue` | Nhắc owner; `abuse_signals` `ROTATION_OVERDUE` |
| `IN_USE` / `AWAITING_RECLAIM` / `ROTATION_*` | Mở `ACCOUNT_TAKEOVER` | `SUSPENDED_SECURITY_REVIEW` | Owner | Mọi đơn tương lai `CONFIRMED` của account sẽ bị hủy khi đến giờ (không `AVAILABLE`) |
| `AVAILABLE` | Đánh dấu nghi ngờ | `SUSPENDED_SECURITY_REVIEW` | Admin/Support | Có `reason_code`, audit |
| `SUSPENDED_SECURITY_REVIEW` | Approval `ACCOUNT_SECURITY_RESTORE` được duyệt | `AVAILABLE` | Dual approval (2 staff khác nhau) | Điều kiện: đã có `CredentialVersion` mới tạo sau thời điểm bị treo (owner đã đổi mật khẩu và khôi phục thông tin) |

**Account nhận đơn mới** chỉ khi `security_state ∈ {AVAILABLE, IN_USE}` (đặt trước cho khung giờ tương lai trong lúc đang có người thuê). Các trạng thái `PENDING_VERIFICATION`, `AWAITING_RECLAIM`, `ROTATION_PENDING`, `ROTATION_OVERDUE`, `SUSPENDED_SECURITY_REVIEW` → `409 ACCOUNT_NOT_AVAILABLE`.

### 6.4. Listing

```mermaid
stateDiagram-v2
    [*] --> DRAFT : owner tao listing
    DRAFT --> PENDING_REVIEW : owner submit
    PENDING_REVIEW --> ACTIVE : admin approve
    PENDING_REVIEW --> REJECTED : admin reject
    REJECTED --> DRAFT : owner sua (PUT)
    ACTIVE --> PAUSED : owner pause
    PAUSED --> PENDING_REVIEW : owner submit lai
    ACTIVE --> PENDING_REVIEW : owner sua noi dung quan trong
    ACTIVE --> SUSPENDED : admin suspend
    PAUSED --> SUSPENDED : admin suspend
    PENDING_REVIEW --> SUSPENDED : admin suspend
    SUSPENDED --> [*]
```

| Từ | Sự kiện | Đến | Kích hoạt | Điều kiện |
|---|---|---|---|---|
| — | `POST /listings` | `DRAFT` | Owner | KYC `VERIFIED`, account thuộc owner, `deposit_amount ∈ [deposit_min, deposit_max]`, `auto_accept = false` nếu `handover_mode = OWNER_ASSISTED_OTP` |
| `DRAFT` / `PAUSED` | `POST /listings/{id}/submit` | `PENDING_REVIEW` | Owner | Game `ALLOWED`, GameAccount đã `verify-recovery` (không ở `PENDING_VERIFICATION`/`SUSPENDED_SECURITY_REVIEW`) |
| `PENDING_REVIEW` | `POST /admin/listings/{id}/approve` | `ACTIVE` | Admin | Không có listing `ACTIVE` khác cùng account (unique index) |
| `PENDING_REVIEW` | `POST /admin/listings/{id}/reject` | `REJECTED` | Admin | Có `review_note` |
| `REJECTED` | `PUT /listings/{id}` | `DRAFT` | Owner | |
| `ACTIVE` | `PUT /listings/{id}` đổi giá, cọc, `end_mode`, mô tả | `PENDING_REVIEW` | Owner | Đơn đã đặt giữ nguyên `policy_snapshot` |
| `ACTIVE` | `POST /listings/{id}/pause` | `PAUSED` | Owner | Đơn đã xác nhận vẫn được thực hiện |
| `ACTIVE` / `PAUSED` / `PENDING_REVIEW` | `POST /admin/listings/{id}/suspend` | `SUSPENDED` | Admin | `reason_code`, audit; đơn tương lai xử lý theo quyết định của Support |

Search và tạo đơn chỉ chấp nhận listing `ACTIVE` **và** game `tos_status = ALLOWED` **và** GameAccount nhận đơn được (§6.3). Đổi `tos_status` của game sang `PROHIBITED`/`SUSPENDED` không cần sửa từng listing: điều kiện fail-closed ở query và ở `startDueOrders` đã chặn.

### 6.5. Dispute

```mermaid
stateDiagram-v2
    [*] --> OPEN : mo dispute dung loai va dung thoi han
    OPEN --> OPEN : bo sung bang chung
    OPEN --> RESOLVED : support quyet dinh (khau tru hoac hoan)
    OPEN --> REJECTED : support bac bo
    RESOLVED --> [*]
    REJECTED --> [*]
```

| Loại | Ai mở | Khi nào được mở | Tác động khi mở | Kết quả có thể |
|---|---|---|---|---|
| `RENTER_LOGIN_FAILED` | Renter | Order `IN_RENTAL` và trong `complaint_window_minutes` kể từ `credential_first_viewed_at`, chưa `handover_confirmed_at` | Tiền thuê không được release cho seller (giữ trong `RENT_ESCROW` hoặc chuyển `SELLER_DISPUTE_HOLD`) | `rent_refund` (thường 100%), `abuse_signals` `OWNER_LOGIN_FAILED_CONFIRMED` |
| `RENTER_NOT_AS_DESCRIBED` | Renter | Như trên | Như trên | `rent_refund` một phần/toàn bộ |
| `OWNER_DAMAGE` | Owner | Order `ENDED` và `now < deposits.hold_until` | Deposit `POST_RENTAL_HOLD → DISPUTED` | `deposit_deduction ≤ deposit_amount` |
| `ACCOUNT_TAKEOVER` | Owner | Order `IN_RENTAL`, hoặc `ENDED` và `now < deposits.hold_until` | Order `ENDED` ngay (nếu đang thuê), deposit `DISPUTED`, GameAccount `SUSPENDED_SECURITY_REVIEW`, renter `RESTRICTED` | `deposit_deduction` tới toàn bộ cọc; quyết định trạng thái renter |

| Từ | Sự kiện | Đến | Kích hoạt |
|---|---|---|---|
| — | `POST /orders/{id}/disputes` | `OPEN` (`deadline_at` = now + SLA phân xử) | Renter/Owner của đơn |
| `OPEN` | `POST /disputes/{id}/evidence` | `OPEN` | Hai bên |
| `OPEN` | `POST /support/disputes/{id}/resolve` (`decision = RESOLVE`, `depositDeduction`, `rentRefund`, `reasonCode`) | `RESOLVED` | Support |
| `OPEN` | `POST /support/disputes/{id}/resolve` (`decision = REJECT`) | `REJECTED` | Support |

### 6.6. Withdrawal

```mermaid
stateDiagram-v2
    [*] --> REQUESTED : owner yeu cau rut, amount nho hon hoac bang available
    REQUESTED --> PAID : finance mark-paid
    REQUESTED --> FAILED : finance mark-failed
    PAID --> [*]
    FAILED --> [*]
```

| Từ | Sự kiện | Đến | Kích hoạt | Bút toán |
|---|---|---|---|---|
| — | `POST /wallet/withdrawals` | `REQUESTED` | Owner (KYC `VERIFIED`, `ACTIVE`) | `WITHDRAWAL_REQUESTED` |
| `REQUESTED` | `POST /finance/withdrawals/{id}/mark-paid` | `PAID` | Finance | `WITHDRAWAL_PAID` |
| `REQUESTED` | `POST /finance/withdrawals/{id}/mark-failed` | `FAILED` | Finance | `WITHDRAWAL_FAILED` (đảo về `SELLER_AVAILABLE`) |

### 6.7. ApprovalRequest

```mermaid
stateDiagram-v2
    [*] --> PENDING : staff tao yeu cau co reason_code
    PENDING --> APPROVED : staff khac duyet
    PENDING --> REJECTED : staff khac tu choi hoac het han
    APPROVED --> EXECUTED : thuc thi thanh cong
    APPROVED --> REJECTED : thuc thi that bai do dieu kien khong con dung
    EXECUTED --> [*]
    REJECTED --> [*]
```

| Từ | Sự kiện | Đến | Kích hoạt | Ghi chú |
|---|---|---|---|---|
| — | `POST /admin/approvals` (hoặc tạo gián tiếp, ví dụ đổi `tos_status` sang `ALLOWED` từ `PROHIBITED`/`SUSPENDED`) | `PENDING` | Staff | `expires_at` mặc định 72 giờ |
| `PENDING` | `POST /admin/approvals/{id}/approve` | `APPROVED` | Staff khác `requested_by`, đúng vai trò (§8.10) | Tự duyệt → `403 SELF_APPROVAL_FORBIDDEN`; DB CHECK chặn lần nữa |
| `APPROVED` | Thực thi hành động trong cùng transaction | `EXECUTED` | Hệ thống | Audit `APPROVAL_EXECUTED` |
| `APPROVED` | Điều kiện nghiệp vụ không còn đúng khi thực thi | `REJECTED` (`reason_code = EXECUTION_PRECONDITION_FAILED`) | Hệ thống | Transaction thực thi rollback, ghi kết quả ở transaction riêng |
| `PENDING` | `POST /admin/approvals/{id}/reject` | `REJECTED` | Staff khác `requested_by` | |
| `PENDING` | Duyệt khi `now > expires_at` | `REJECTED` (`APPROVAL_EXPIRED`) | Hệ thống | Kiểm tra tại thời điểm duyệt |

---

## 7. Ledger kế toán kép

### 7.1. Nguyên tắc

1. **Bất biến:** chỉ INSERT vào `ledger_transactions` và `ledger_postings`; sai thì ghi bút toán bù (`LEDGER_ADJUSTMENT` qua dual approval), không sửa/xóa (trigger chặn).
2. **Cân bằng:** mỗi `LedgerTransaction` có ≥ 2 posting, tổng `amount` = 0. Kiểm tra ở `LedgerService.post` và bằng constraint trigger deferred khi COMMIT.
3. **Quy ước dấu:** `posting.amount` có dấu — **dương = Nợ (debit)**, **âm = Có (credit)**. Không có posting `amount = 0`.
4. **Nguyên tử với nghiệp vụ:** bút toán được ghi trong cùng DB transaction với chuyển trạng thái order/deposit/withdrawal. Không có trạng thái "đã chuyển trạng thái nhưng chưa ghi sổ".
5. **Idempotent:** mỗi transaction có `idempotency_key` xác định (ví dụ `PAYMENT_CAPTURED:{intentId}`, `ORDER_ENDED:{orderId}`, `DEPOSIT_REFUND_REQUESTED:{depositId}:{seq}`, `WITHDRAWAL_REQUESTED:{withdrawalId}`). Job hoặc webhook chạy lại sẽ va unique index và bị bỏ qua an toàn.
6. **Tách theo đơn:** posting mang `order_id`, nên số dư `RENT_ESCROW` và `DEPOSIT_HELD` luôn tách được theo từng order (quan trọng cho R5: cọc của mỗi đơn là một phần riêng).
7. Ledger là **nguồn sự thật về tiền**; cột tiền trên `deposits`/`orders` là snapshot nghiệp vụ, phải đối soát khớp với ledger (§16).

### 7.2. Danh sách tài khoản

| Code | Phạm vi | Bản chất | Số dư bình thường | Ý nghĩa |
|---|---|---|---|---|
| `PSP_CLEARING` | Hệ thống | Tài sản | Dương (Nợ) | Tiền đang nằm ở PSP/tài khoản thu hộ của nền tảng |
| `RENT_ESCROW` | Hệ thống | Nợ phải trả | Âm (Có) | Tiền thuê đã thu, chờ đơn kết thúc hoặc hoàn |
| `DEPOSIT_HELD` | Hệ thống (tách theo `order_id`) | Nợ phải trả renter | Âm (Có) | Cọc của renter. **Không phải doanh thu.** |
| `REFUND_PAYABLE` | Hệ thống | Nợ phải trả | Âm (Có) | Khoản đã quyết định hoàn, chờ PSP xác nhận |
| `PLATFORM_REVENUE` | Hệ thống | Doanh thu | Âm (Có) | Hoa hồng nền tảng |
| `SELLER_REVIEW_HOLD:{seller}` | Theo seller | Nợ phải trả seller | Âm (Có) | Doanh thu seller đang trong hold review |
| `SELLER_DISPUTE_HOLD:{seller}` | Theo seller | Nợ phải trả seller | Âm (Có) | Doanh thu bị giữ do dispute của renter |
| `SELLER_AVAILABLE:{seller}` | Theo seller | Nợ phải trả seller | Âm (Có) | Số dư khả dụng để rút (gồm cả khoản khấu trừ cọc được bồi thường) |
| `SELLER_PAYOUT_PROCESSING:{seller}` | Theo seller | Nợ phải trả seller | Âm (Có) | Đã yêu cầu rút, chờ Finance chi |

Tài khoản `SELLER_*` được tạo lười (lazy) lần đầu seller cần, với unique `(code, owner_user_id)`.

### 7.3. Bút toán theo sự kiện

Ký hiệu: `+` = Nợ, `−` = Có; `rent`, `fee`, `net`, `d` lấy từ order/deposit.

| Transaction type | Khi nào | Posting | Tổng |
|---|---|---|---|
| `PAYMENT_CAPTURED` | Webhook thanh toán thành công | `+PSP_CLEARING (rent + d)`; `−RENT_ESCROW rent`; `−DEPOSIT_HELD d` | 0 |
| `ORDER_ENDED` | Order `ENDED` (job hoặc takeover) | `+RENT_ESCROW rent`; `−SELLER_REVIEW_HOLD:{s} net`; `−PLATFORM_REVENUE fee` | 0 |
| `SELLER_HOLD_RELEASED` | `now ≥ seller_hold_until`, không có dispute renter đang mở; hoặc approval `SELLER_HOLD_EARLY_RELEASE` | `+SELLER_REVIEW_HOLD:{s} x`; `−SELLER_AVAILABLE:{s} x` | 0 |
| `SELLER_DISPUTE_HOLD` | Dispute renter mở khi doanh thu còn trong review hold | `+SELLER_REVIEW_HOLD:{s} x`; `−SELLER_DISPUTE_HOLD:{s} x` | 0 |
| `SELLER_DISPUTE_HOLD_RELEASED` | Dispute renter đóng, phần doanh thu còn lại trả về review hold | `+SELLER_DISPUTE_HOLD:{s} y`; `−SELLER_REVIEW_HOLD:{s} y` | 0 |
| `DEPOSIT_REFUND_REQUESTED` | Hết hold cọc / phân xử không khấu trừ / phần còn lại sau khấu trừ | `+DEPOSIT_HELD d'`; `−REFUND_PAYABLE d'` | 0 |
| `REFUND_COMPLETED` | PSP xác nhận một `Refund` thành công | `+REFUND_PAYABLE a`; `−PSP_CLEARING a` | 0 |
| `DEPOSIT_DEDUCTED` | Phân xử khấu trừ cọc bồi thường chủ | `+DEPOSIT_HELD x`; `−SELLER_AVAILABLE:{s} x` | 0 |
| `RENT_REFUND` | Phân xử hoàn tiền thuê `r` | Nếu đơn chưa kết thúc: `+RENT_ESCROW r`; `−REFUND_PAYABLE r`. Nếu đã vào dispute hold: `+SELLER_DISPUTE_HOLD:{s} (r − fr)`; `+PLATFORM_REVENUE fr`; `−REFUND_PAYABLE r`, với `fr = floor(r × fee / rent)` (hoa hồng được hoàn theo tỷ lệ) | 0 |
| `ORDER_CANCELLED_REFUND` | Hủy/từ chối sau khi đã thanh toán | `+RENT_ESCROW refundRent`; `+DEPOSIT_HELD d`; `−REFUND_PAYABLE (refundRent + d)` | 0 |
| `ORDER_CANCELLED_REVENUE` | Hủy muộn của renter: phần tiền thuê không hoàn | `+RENT_ESCROW kept`; `−SELLER_REVIEW_HOLD:{s} (kept − feeK)`; `−PLATFORM_REVENUE feeK`, với `kept = rent − refundRent`, `feeK = floor(kept × bps / 10000)` | 0 |
| `WITHDRAWAL_REQUESTED` | Owner yêu cầu rút | `+SELLER_AVAILABLE:{s} a`; `−SELLER_PAYOUT_PROCESSING:{s} a` | 0 |
| `WITHDRAWAL_PAID` | Finance đánh dấu đã chi | `+SELLER_PAYOUT_PROCESSING:{s} a`; `−PSP_CLEARING a` | 0 |
| `WITHDRAWAL_FAILED` | Finance đánh dấu thất bại | `+SELLER_PAYOUT_PROCESSING:{s} a`; `−SELLER_AVAILABLE:{s} a` | 0 |
| `LEDGER_ADJUSTMENT` | Chỉ khi approval `LEDGER_ADJUSTMENT` được thực thi | Danh sách posting trong payload (tổng = 0) | 0 |

Các bước không có bút toán: tạo order, deposit `HELD → POST_RENTAL_HOLD`, deposit `→ DISPUTED`, order `EXPIRED` (chưa thu tiền).

Công thức tiền (chốt khi tạo đơn, lưu vào order và `policy_snapshot`):

```
rent_amount  = floor(price_per_hour × duration_minutes / 60)
platform_fee = floor(rent_amount × platform_fee_bps / 10000)
seller_net   = rent_amount − platform_fee
amount_to_pay = rent_amount + deposit_amount      // renter không trả phí riêng
```

### 7.4. Ví dụ 1 — Đơn bình thường

Listing: 30.000đ/giờ, cọc 200.000đ, phí 10%, `FORCE_AT_END`. Renter R thuê 3 giờ của seller S.
`rent = 90.000`, `fee = 9.000`, `net = 81.000`, `d = 200.000`, renter trả **290.000đ**.

| Bước | Thời điểm | Transaction | Posting (+ Nợ / − Có) |
|---|---|---|---|
| 1 | T0 | `PAYMENT_CAPTURED` | `+PSP_CLEARING 290.000`; `−RENT_ESCROW 90.000`; `−DEPOSIT_HELD 200.000` |
| 2 | T1 = kết thúc thuê | `ORDER_ENDED` | `+RENT_ESCROW 90.000`; `−SELLER_REVIEW_HOLD:S 81.000`; `−PLATFORM_REVENUE 9.000` |
| — | T1 | (không bút toán) | Deposit `POST_RENTAL_HOLD`, `hold_until = T1 + 72h`; `seller_hold_until = T1 + 72h` |
| 3 | T1 + 72h | `SELLER_HOLD_RELEASED` | `+SELLER_REVIEW_HOLD:S 81.000`; `−SELLER_AVAILABLE:S 81.000` |
| 4 | T1 + 72h | `DEPOSIT_REFUND_REQUESTED` | `+DEPOSIT_HELD 200.000`; `−REFUND_PAYABLE 200.000` |
| 5 | PSP xác nhận | `REFUND_COMPLETED` | `+REFUND_PAYABLE 200.000`; `−PSP_CLEARING 200.000` |
| 6 | S rút tiền | `WITHDRAWAL_REQUESTED` | `+SELLER_AVAILABLE:S 81.000`; `−SELLER_PAYOUT_PROCESSING:S 81.000` |
| 7 | Finance chi | `WITHDRAWAL_PAID` | `+SELLER_PAYOUT_PROCESSING:S 81.000`; `−PSP_CLEARING 81.000` |

Số dư (Σ amount) sau từng bước:

| Tài khoản | Sau 1 | Sau 2 | Sau 3 | Sau 4 | Sau 5 | Sau 6 | Sau 7 |
|---|---|---|---|---|---|---|---|
| `PSP_CLEARING` | +290.000 | +290.000 | +290.000 | +290.000 | +90.000 | +90.000 | **+9.000** |
| `RENT_ESCROW` | −90.000 | 0 | 0 | 0 | 0 | 0 | 0 |
| `DEPOSIT_HELD` | −200.000 | −200.000 | −200.000 | 0 | 0 | 0 | 0 |
| `REFUND_PAYABLE` | 0 | 0 | 0 | −200.000 | 0 | 0 | 0 |
| `SELLER_REVIEW_HOLD:S` | 0 | −81.000 | 0 | 0 | 0 | 0 | 0 |
| `SELLER_AVAILABLE:S` | 0 | 0 | −81.000 | −81.000 | −81.000 | 0 | 0 |
| `SELLER_PAYOUT_PROCESSING:S` | 0 | 0 | 0 | 0 | 0 | −81.000 | 0 |
| `PLATFORM_REVENUE` | 0 | −9.000 | −9.000 | −9.000 | −9.000 | −9.000 | **−9.000** |
| **Tổng** | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Kết quả cuối: còn 9.000đ ở PSP đúng bằng doanh thu nền tảng; renter đã nhận lại 200.000đ cọc; seller đã nhận 81.000đ.

### 7.5. Ví dụ 2 — Khiếu nại thiệt hại, khấu trừ 120.000đ cọc

Cùng đơn như ví dụ 1 (bước 1–2 giống hệt). Tại T1 + 20h, S mở `OWNER_DAMAGE` (rank bị tụt, có ảnh chụp trước/sau) → deposit `POST_RENTAL_HOLD → DISPUTED`. Dispute này nhắm vào cọc nên **không** ảnh hưởng hold doanh thu: tại T1 + 72h doanh thu 81.000 vẫn release (bước 3 như ví dụ 1). Job `releaseDeposits` bỏ qua cọc vì đang `DISPUTED`.

Tại T1 + 90h Support phân xử: khấu trừ 120.000đ, hoàn 80.000đ.

| Bước | Transaction | Posting |
|---|---|---|
| 4' | `DEPOSIT_DEDUCTED` | `+DEPOSIT_HELD 120.000`; `−SELLER_AVAILABLE:S 120.000` |
| 5' | `DEPOSIT_REFUND_REQUESTED` | `+DEPOSIT_HELD 80.000`; `−REFUND_PAYABLE 80.000` |
| 6' | `REFUND_COMPLETED` | `+REFUND_PAYABLE 80.000`; `−PSP_CLEARING 80.000` |

Bước 4' và 5' nằm trong cùng DB transaction với việc dispute → `RESOLVED` và deposit → `SETTLED` (`deducted_amount = 120.000`, `refunded_amount = 80.000`).

| Tài khoản | Sau bước 3 | Sau 4' | Sau 5' | Sau 6' |
|---|---|---|---|---|
| `PSP_CLEARING` | +290.000 | +290.000 | +290.000 | +210.000 |
| `DEPOSIT_HELD` | −200.000 | −80.000 | 0 | 0 |
| `REFUND_PAYABLE` | 0 | 0 | −80.000 | 0 |
| `SELLER_AVAILABLE:S` | −81.000 | −201.000 | −201.000 | −201.000 |
| `PLATFORM_REVENUE` | −9.000 | −9.000 | −9.000 | −9.000 |
| **Tổng** | 0 | 0 | 0 | 0 |

Seller có 201.000đ khả dụng (81.000 doanh thu + 120.000 bồi thường). Nếu S rút toàn bộ và Finance chi, `PSP_CLEARING` còn 9.000đ = doanh thu nền tảng. Khoản khấu trừ không phải doanh thu nền tảng.

### 7.6. Ví dụ 3 — Cọc đơn A còn đang giữ, renter thuê đơn B phải trả cọc mới

- Đơn A (seller S): như ví dụ 1, đã `ENDED` tại T1; cọc A 200.000đ đang `POST_RENTAL_HOLD` đến T1 + 72h.
- Tại T1 + 5h, cùng renter R thuê account khác của seller S2: 25.000đ/giờ × 2 giờ, cọc 150.000đ. `rent = 50.000`, `fee = 5.000`, `net = 45.000`. Renter trả **200.000đ** = 50.000 thuê + 150.000 cọc mới.

Hệ thống **không** cho dùng cọc A cho đơn B: `POST /orders` luôn tạo một `deposits` mới gắn `order_id` của B; `PaymentIntent` của B luôn có `amount = rent + deposit_amount` của B; không có API hay tham số nào để chuyển/dùng lại cọc; không bù chéo khi hoàn hoặc khấu trừ.

| Bước | Transaction | `order_id` | Posting |
|---|---|---|---|
| A1 | `PAYMENT_CAPTURED` | A | `+PSP_CLEARING 290.000`; `−RENT_ESCROW 90.000`; `−DEPOSIT_HELD 200.000` |
| A2 | `ORDER_ENDED` | A | `+RENT_ESCROW 90.000`; `−SELLER_REVIEW_HOLD:S 81.000`; `−PLATFORM_REVENUE 9.000` |
| B1 | `PAYMENT_CAPTURED` | B | `+PSP_CLEARING 200.000`; `−RENT_ESCROW 50.000`; `−DEPOSIT_HELD 150.000` |

Sau B1, tài khoản `DEPOSIT_HELD` có số dư **−350.000**, gồm **hai phần riêng biệt theo order**:

```sql
SELECT p.order_id, -SUM(p.amount) AS deposit_held
  FROM ledger_postings p
  JOIN ledger_accounts a ON a.id = p.account_id
 WHERE a.code = 'DEPOSIT_HELD'
 GROUP BY p.order_id
HAVING SUM(p.amount) <> 0;
-- order_id | deposit_held
-- A        | 200000
-- B        | 150000
```

Tiếp theo:
- T1 + 72h: job hoàn cọc A (`DEPOSIT_REFUND_REQUESTED` 200.000 với `order_id = A`). Cọc B không bị ảnh hưởng.
- Đơn B kết thúc tại T_B; cọc B có `hold_until = T_B + 72h` riêng. Nếu S2 mở `OWNER_DAMAGE` cho B, chỉ phần 150.000 của B có thể bị khấu trừ; tối đa khấu trừ = `deposits(B).amount`. Số tiền cọc A không bao giờ được dùng để bù cho B (kể cả khi A chưa hoàn).
- `GET /wallet/deposits` của R hiển thị hai dòng: A (`POST_RENTAL_HOLD`, `hold_until`), B (`HELD`).

### 7.7. Cách tính số dư

- Số dư tài khoản = `SUM(amount)` trên `ledger_postings` của tài khoản đó.
- Tài khoản nợ phải trả/doanh thu hiển thị **lấy đối dấu** (`−SUM`), nên số hiển thị dương.
- `GET /wallet/balances` của seller trả về:

```json
{
  "reviewHold": 81000,
  "disputeHold": 0,
  "available": 0,
  "payoutProcessing": 0,
  "currency": "VND"
}
```

với `reviewHold = −SUM(SELLER_REVIEW_HOLD:{me})`, tương tự cho các tài khoản còn lại.

```sql
SELECT a.code, -COALESCE(SUM(p.amount), 0) AS balance
  FROM ledger_accounts a
  LEFT JOIN ledger_postings p ON p.account_id = a.id
 WHERE a.owner_user_id = :sellerId
 GROUP BY a.code;
```

- MVP tính trực tiếp bằng `SUM` (index `ledger_postings(account_id)`); khi số posting lớn có thể thêm bảng snapshot số dư theo ngày (Phase 1), không thay đổi nguồn sự thật.
- Rút tiền: khóa hàng `ledger_accounts` của `SELLER_AVAILABLE:{seller}` bằng `SELECT … FOR UPDATE`, tính lại số dư rồi mới ghi `WITHDRAWAL_REQUESTED` (§15.3).
- Bất biến hệ thống (kiểm tra bởi job đối soát, §16): tổng mọi posting = 0; `−SUM(DEPOSIT_HELD theo order)` = `deposits.amount − deducted − refunded-requested` cho mọi deposit đã thanh toán; `RENT_ESCROW` theo order = 0 với order ở trạng thái cuối.

---

## 8. Các luồng kỹ thuật then chốt

Ký hiệu trong sơ đồ: `API` = controller + application service; `DB` = PostgreSQL (một transaction trừ khi ghi chú); `PSP` = `PaymentGateway`; `Outbox` = bảng `outbox_events` + publisher.

### 8.1. Tạo đơn + giữ chỗ + thanh toán + webhook (UC-06, UC-07, UC-25)

```mermaid
sequenceDiagram
    autonumber
    actor R as Renter
    participant API as RentGG API
    participant DB as PostgreSQL
    participant PSP as PaymentGateway
    R->>API: POST /orders (listingId, startAt, durationMinutes) + Idempotency-Key
    API->>DB: Tim order theo (renter_id, idempotency_key)
    alt Da ton tai, cung payload
        API-->>R: 200 order cu
    else Moi
        API->>DB: Doc listing ACTIVE, game ALLOWED, account AVAILABLE hoac IN_USE, user ACTIVE va du tuoi
        API->>API: Tinh rent, fee, net, deposit, block_end_at, policy_snapshot
        API->>DB: INSERT order PENDING_PAYMENT + deposit PENDING_PAYMENT (1 transaction)
        alt Vi pham exclusion constraint (23P01)
            API-->>R: 409 SLOT_UNAVAILABLE
        else OK
            API-->>R: 201 order (paymentExpiresAt = now + 15 phut)
        end
    end
    R->>API: POST /orders/{id}/pay + Idempotency-Key
    API->>DB: Kiem tra PENDING_PAYMENT va chua het han
    API->>PSP: createCheckout(amount = rent + deposit, intentId)
    PSP-->>API: providerRef, checkoutUrl
    API->>DB: INSERT payment_intent CREATED
    API-->>R: 200 checkoutUrl
    R->>PSP: Thanh toan
    PSP->>API: POST /payments/webhook/{provider} + X-Signature
    API->>API: Xac minh HMAC-SHA256 tren raw body
    alt Chu ky sai
        API-->>PSP: 401 (audit WEBHOOK_SIGNATURE_INVALID)
    else Hop le
        API->>DB: SELECT intent FOR UPDATE theo (provider, provider_ref)
        alt Intent da SUCCEEDED
            API-->>PSP: 200 (idempotent, khong lam gi)
        else Order con PENDING_PAYMENT va so tien khop
            API->>DB: intent SUCCEEDED, ledger PAYMENT_CAPTURED, deposit HELD
            API->>DB: order CONFIRMED hoac AWAITING_OWNER_APPROVAL, outbox OrderPaid
            API-->>PSP: 200
        else Order da EXPIRED hoac CANCELLED (thanh toan muon)
            API->>DB: ledger PAYMENT_CAPTURED roi ORDER_CANCELLED_REFUND 100 phan tram
            API->>DB: Tao refunds RENT va DEPOSIT, outbox RefundRequested
            API-->>PSP: 200
        end
    end
```

Điểm then chốt:
- **Giữ chỗ = INSERT order** `PENDING_PAYMENT`. Exclusion constraint bảo đảm không thể có hai đơn hoạt động chồng lấn trên cùng `game_account_id`; không cần lock ứng dụng. Hai request đồng thời: một commit, một nhận `23P01` → `409 SLOT_UNAVAILABLE`.
- Kiểm tra trước khi insert: renter ≠ owner; `durationMinutes ∈ [min, max]`; `startAt ≥ now`; renter `status = ACTIVE` (không `RESTRICTED`) và tuổi ≥ `min_age`; listing `ACTIVE`; game `ALLOWED`; GameAccount `AVAILABLE`/`IN_USE`.
- Webhook so sánh `amount` với `payment_intents.amount`; lệch → không xác nhận, cảnh báo.
- Thanh toán đến muộn (order đã `EXPIRED`) vẫn được ghi nhận rồi hoàn 100% với `cancel_reason = LATE_PAYMENT`, vì khung giờ có thể đã được người khác giữ.
- Profile `dev`: `POST /dev/payments/{intentId}/simulate-success` gọi cùng handler như webhook hợp lệ.

### 8.2. AUTO_ACCEPT và duyệt thủ công (UC-38)

```mermaid
sequenceDiagram
    autonumber
    participant W as Webhook handler
    participant DB as PostgreSQL
    participant N as Notification
    actor O as Owner
    participant J as Job expireOwnerApprovals
    W->>DB: Doc policy_snapshot.auto_accept
    alt auto_accept = true (chi khi handover_mode = STATIC_CREDENTIAL)
        W->>DB: order CONFIRMED, outbox OrderConfirmed
        DB-->>N: Thong bao renter va owner
    else auto_accept = false
        W->>DB: order AWAITING_OWNER_APPROVAL, approval_expires_at = now + 30 phut
        DB-->>N: Thong bao owner can duyet
        alt Owner duyet
            O->>W: POST /orders/{id}/approve
            W->>DB: order CONFIRMED (kiem tra version), outbox OrderConfirmed
        else Owner tu choi
            O->>W: POST /orders/{id}/reject
            W->>DB: order REJECTED OWNER_REJECTED, ORDER_CANCELLED_REFUND 100 phan tram
            W->>DB: deposit REFUND_PENDING, outbox RefundRequested
        else Qua 30 phut
            J->>DB: order AWAITING_OWNER_APPROVAL va approval_expires_at nho hon now
            J->>DB: order REJECTED APPROVAL_TIMEOUT, hoan 100 phan tram thue va coc
        end
    end
```

- `auto_accept` là sự đồng ý trước của chủ (UC-38): chỉ đơn khớp đúng giá, cọc, `end_mode`, thời lượng trong listing đã duyệt mới được xác nhận tự động.
- Listing không bật được `auto_accept` khi game có `handover_mode = OWNER_ASSISTED_OTP` (`422 AUTO_ACCEPT_NOT_ALLOWED`), vì mỗi lần đăng nhập cần chủ có mặt.

### 8.3. Bắt đầu đơn + xem credentials (UC-08, UC-30, UC-33)

```mermaid
sequenceDiagram
    autonumber
    participant J as Job startDueOrders
    participant DB as PostgreSQL
    actor R as Renter
    participant API as RentGG API
    participant C as CredentialCipher
    actor O as Owner
    J->>DB: SELECT orders CONFIRMED va start_at nho hon hoac bang now FOR UPDATE SKIP LOCKED
    J->>DB: SELECT game_account FOR UPDATE
    alt Account AVAILABLE va game ALLOWED
        J->>DB: order IN_RENTAL, account IN_USE, outbox OrderStarted
    else Account chua AVAILABLE
        J->>DB: order CANCELLED OWNER_FAULT_NOT_READY, hoan 100 phan tram, abuse_signal
    end
    R->>API: GET /orders/{id}/credentials (JWT)
    API->>API: Rate limit 10 lan moi gio moi order (Bucket4j)
    API->>DB: Kiem tra renter cua don, status IN_RENTAL, now trong [start_at, block_end_at]
    API->>DB: Doc credential_versions ACTIVE cua account
    API->>C: decrypt(ciphertext, iv, key_id)
    C-->>API: username, password (chi trong bo nho)
    API->>DB: INSERT audit_logs CREDENTIAL_VIEWED, set credential_first_viewed_at neu null
    API-->>R: 200 username, password, complaintWindowEndsAt (Cache-Control no-store)
    opt Game OWNER_ASSISTED_OTP
        R->>API: POST /orders/{id}/otp-request
        API->>DB: outbox OtpRequested
        DB-->>O: Thong bao owner gui ma qua kenh cua nen tang
    end
    alt Dang nhap thanh cong
        R->>API: POST /orders/{id}/handover/confirm
        API->>DB: handover_confirmed_at = now (dong complaint window som)
    else Dang nhap that bai trong complaint window
        R->>API: POST /orders/{id}/disputes type RENTER_LOGIN_FAILED
    end
```

Quy tắc xem credentials (chi tiết §12.4): chỉ renter của đơn; chỉ khi `IN_RENTAL` và `now ∈ [start_at, block_end_at]`; JWT hợp lệ + rate limit + audit mỗi lần; response có `Cache-Control: no-store`; không log body. Lần xem đầu ghi `credential_first_viewed_at` → complaint window `= credential_first_viewed_at + complaint_window_minutes`.

OTP (game `OWNER_ASSISTED_OTP`): nền tảng không sinh, không chặn, không tự lấy OTP của nhà phát hành. Renter bấm "yêu cầu mã" → owner nhận thông báo → owner tự đưa mã cho renter qua kênh ghi chú trong order; nội dung không được lưu lâu dài.

### 8.4. Kết thúc phiên theo 3 end mode + rotation thủ công (UC-09, UC-37, UC-39)

```mermaid
sequenceDiagram
    autonumber
    participant J as Job endDueOrders
    participant DB as PostgreSQL
    participant N as Notification
    actor R as Renter
    actor O as Owner
    participant API as RentGG API
    Note over J,DB: sendEndingReminders gui nhac 10 phut truoc scheduled_end_at
    J->>DB: SELECT orders IN_RENTAL va block_end_at nho hon hoac bang now
    J->>DB: order ENDED, actual_end_at, ledger ORDER_ENDED
    J->>DB: deposit POST_RENTAL_HOLD hold_until = actual_end_at + deposit_hold_hours
    J->>DB: seller_hold_until = actual_end_at + seller_hold_hours
    alt FORCE_AT_END hoac GRACE_FOR_CURRENT_MATCH
        J->>DB: account ROTATION_PENDING, rotation_due_at = now + 60 phut
        DB-->>N: Bao owner doi mat khau ngay, bao renter phai thoat
    else STAY_ONLINE_UNTIL_RECLAIM
        J->>DB: account AWAITING_RECLAIM
        DB-->>N: Bao owner co the thu hoi bat ky luc nao
        O->>API: POST /game-accounts/{id}/reclaim
        API->>DB: account ROTATION_PENDING, rotation_due_at
    end
    O->>O: Doi mat khau tren he thong cua nha phat hanh, dang xuat moi thiet bi
    O->>API: POST /game-accounts/{id}/rotation (newPassword, loggedOutAllDevices = true)
    API->>DB: credential cu RETIRED, INSERT credential moi ACTIVE (version + 1)
    API->>DB: account AVAILABLE, audit ROTATION_COMPLETED
    Note over J,DB: Neu qua rotation_due_at ma chua rotation thi markRotationOverdue chuyen ROTATION_OVERDUE va nhac lai
```

| End mode | `block_end_at` | Khi `ENDED` | Ghi chú cho renter |
|---|---|---|---|
| `FORCE_AT_END` | `= scheduled_end_at` | Account → `ROTATION_PENDING` ngay | Phải thoát đúng giờ; mật khẩu sẽ bị đổi |
| `GRACE_FOR_CURRENT_MATCH` | `= scheduled_end_at + grace_minutes` | Account → `ROTATION_PENDING` | Được hoàn tất trận đang chơi trong ân hạn; **không được bắt đầu trận mới** sau `scheduled_end_at` (nhắc lúc gửi reminder) |
| `STAY_ONLINE_UNTIL_RECLAIM` | `= scheduled_end_at + grace_minutes` | Account → `AWAITING_RECLAIM`; listing không nhận đơn mới | Có thể vẫn trong game cho đến khi owner thu hồi và đổi mật khẩu |

- Đổi mật khẩu là cơ chế kết thúc phiên duy nhất. Hiệu quả phụ thuộc `password_change_kicks_sessions` của game.
- Rotation chỉ nhận mật khẩu mới do owner nhập (owner tự đổi trên game trước). Hệ thống không tự đăng nhập để kiểm tra; nếu mật khẩu sai, đơn kế tiếp sẽ có `RENTER_LOGIN_FAILED` và owner bị ghi tín hiệu.
- Vì đơn kế tiếp có thể đã được đặt (khi account còn `IN_USE`), nếu owner không rotation trước `start_at` của đơn đó → đơn bị hủy `OWNER_FAULT_NOT_READY`, hoàn 100% (§8.3).

### 8.5. Giữ cọc sau thuê + khiếu nại thiệt hại + hoàn cọc (UC-11, UC-13, UC-25, UC-27)

```mermaid
sequenceDiagram
    autonumber
    actor O as Owner
    actor R as Renter
    actor S as Support
    participant API as RentGG API
    participant DB as PostgreSQL
    participant J as Job releaseDeposits
    participant PSP as PaymentGateway
    Note over DB: Deposit POST_RENTAL_HOLD den hold_until = actual_end_at + 72h
    alt Owner mo OWNER_DAMAGE truoc hold_until
        O->>API: POST /orders/{id}/disputes type OWNER_DAMAGE, evidenceUrls
        API->>DB: SELECT deposit FOR UPDATE, kiem tra POST_RENTAL_HOLD va now nho hon hold_until
        API->>DB: INSERT dispute OPEN, deposit DISPUTED, outbox DisputeOpened
        R->>API: POST /disputes/{id}/evidence (phan hoi)
        S->>API: POST /support/disputes/{id}/resolve (depositDeduction = x, reasonCode)
        API->>DB: dispute RESOLVED, audit DISPUTE_RESOLVED
        alt x lon hon 0
            API->>DB: ledger DEPOSIT_DEDUCTED x, deposit SETTLED
            API->>DB: neu con du thi DEPOSIT_REFUND_REQUESTED va refund DEPOSIT
        else x = 0 hoac dispute REJECTED
            API->>DB: ledger DEPOSIT_REFUND_REQUESTED toan bo, deposit REFUND_PENDING
        end
    else Khong co dispute
        J->>DB: SELECT deposits POST_RENTAL_HOLD va hold_until nho hon hoac bang now
        J->>DB: ledger DEPOSIT_REFUND_REQUESTED, deposit REFUND_PENDING, refund PENDING
    end
    DB-->>PSP: Outbox RefundRequested, consumer goi refund(providerRef, amount)
    PSP-->>API: Thanh cong
    API->>DB: refund SUCCEEDED, ledger REFUND_COMPLETED, deposit REFUNDED neu dang REFUND_PENDING
```

- Mức khấu trừ tối đa bằng `deposits.amount` của **chính đơn đó**; thiệt hại vượt cọc không được trừ sang cọc đơn khác hay tiền khác của renter trên nền tảng (xử lý ngoài nền tảng/pháp lý).
- Owner mở dispute sau `hold_until` → `422 DISPUTE_WINDOW_CLOSED`.
- Refund PSP lỗi: `refunds.status = FAILED`, consumer thử lại với backoff; quá số lần → alert Finance; trạng thái cọc vẫn `REFUND_PENDING`, số tiền vẫn nằm ở `REFUND_PAYABLE`.

### 8.6. Renter thuê đơn thứ hai khi cọc cũ còn đang giữ (R5)

```mermaid
sequenceDiagram
    autonumber
    actor R as Renter
    participant API as RentGG API
    participant DB as PostgreSQL
    participant PSP as PaymentGateway
    Note over DB: Deposit A 200000 POST_RENTAL_HOLD, order A ENDED
    R->>API: POST /orders listing B (account khac)
    API->>DB: INSERT order B PENDING_PAYMENT
    API->>DB: INSERT deposit moi (order_id = B, amount = deposit cua listing B)
    API-->>R: 201 amountToPay = rentB + depositB
    Note over API: Khong co tham so dung lai coc. Khong tru coc A. Khong co so du coc dung chung.
    R->>API: POST /orders/B/pay
    API->>PSP: createCheckout(rentB + depositB)
    PSP->>API: webhook SUCCEEDED
    API->>DB: ledger PAYMENT_CAPTURED order_id = B, deposit B HELD
    Note over DB: DEPOSIT_HELD = phan A 200000 + phan B 150000, tach theo order_id
    R->>API: GET /wallet/deposits
    API-->>R: Danh sach 2 coc rieng voi status va hold_until
```

- Không có ràng buộc "phải chờ hoàn cọc A mới thuê B": renter thuê được (nếu không `RESTRICTED`) nhưng phải trả cọc mới.
- Nếu đơn A có dispute `ACCOUNT_TAKEOVER` → renter `RESTRICTED` → `POST /orders` trả `403 USER_RESTRICTED`.

### 8.7. Chiếm account trong lúc thuê (UC-43)

```mermaid
sequenceDiagram
    autonumber
    actor O as Owner
    participant API as RentGG API
    participant DB as PostgreSQL
    participant N as Notification
    actor S as Support
    actor A2 as Admin thu hai
    O->>O: Phat hien mat khau hoac email khoi phuc bi doi
    O->>O: Dung thong tin khoi phuc minh nam giu de lay lai account (R2)
    O->>API: POST /orders/{id}/disputes type ACCOUNT_TAKEOVER, evidenceUrls
    API->>DB: SELECT order, deposit, game_account, renter FOR UPDATE
    alt Order IN_RENTAL
        API->>DB: order ENDED actual_end_at = now, ledger ORDER_ENDED
    end
    API->>DB: deposit DISPUTED, account SUSPENDED_SECURITY_REVIEW
    API->>DB: renter status RESTRICTED, dispute OPEN (uu tien khan)
    API->>DB: audit ACCOUNT_TAKEOVER_REPORTED, outbox DisputeOpened
    DB-->>N: Bao Support (khan), bao renter
    Note over API: Credentials khong con xem duoc vi order da ENDED
    S->>API: POST /support/disputes/{id}/resolve (depositDeduction toi da toan bo coc)
    API->>DB: DEPOSIT_DEDUCTED, abuse_signal RENTER_TAKEOVER_CONFIRMED, quyet dinh status renter
    O->>API: POST /game-accounts/{id}/rotation (mat khau moi sau khi lay lai)
    Note over API,DB: Account van SUSPENDED_SECURITY_REVIEW, chi tao credential version moi
    S->>API: POST /admin/approvals ACCOUNT_SECURITY_RESTORE
    A2->>API: POST /admin/approvals/{id}/approve
    API->>DB: account AVAILABLE, approval EXECUTED
```

- R2 là biện pháp chính: vì chủ giữ email/SĐT/2FA khôi phục, renter đổi mật khẩu cũng không giữ được account lâu.
- Mở được trong lúc `IN_RENTAL` hoặc trong thời gian giữ cọc (khi chủ phát hiện muộn).
- Doanh thu của đơn vẫn ghi `ORDER_ENDED` (renter đã dùng dịch vụ); cọc là nguồn bồi thường. Khôi phục account cần dual approval và credential version mới.
- Trong trạng thái `SUSPENDED_SECURITY_REVIEW`, endpoint rotation chấp nhận credential mới nhưng **không** đưa account về `AVAILABLE`.

### 8.8. Hủy đơn (UC-17, UC-33, UC-40)

```mermaid
sequenceDiagram
    autonumber
    actor U as Renter hoac Owner
    participant API as RentGG API
    participant DB as PostgreSQL
    U->>API: POST /orders/{id}/cancel (reason)
    API->>DB: SELECT order FOR UPDATE
    alt status IN_RENTAL, ENDED hoac trang thai cuoi
        API-->>U: 409 INVALID_STATE (dung dispute)
    else status PENDING_PAYMENT (chi renter)
        API->>DB: order CANCELLED RENTER_CANCELLED, deposit VOID, intent huy
    else Renter huy, da thanh toan
        alt start_at - now lon hon hoac bang renter_free_cancel_minutes
            API->>DB: ORDER_CANCELLED_REFUND rent 100 phan tram + coc
        else Huy muon
            API->>DB: ORDER_CANCELLED_REFUND rent x late_cancel_refund_bps + coc
            API->>DB: ORDER_CANCELLED_REVENUE phan con lai cho seller, seller_hold_until
        end
        API->>DB: order CANCELLED, deposit REFUND_PENDING (khong giu them)
    else Owner huy (CONFIRMED hoac AWAITING_OWNER_APPROVAL)
        API->>DB: ORDER_CANCELLED_REFUND 100 phan tram thue + coc
        API->>DB: order CANCELLED OWNER_CANCELLED, deposit REFUND_PENDING
        opt start_at - now nho hon owner_free_cancel_hours
            API->>DB: abuse_signal OWNER_LATE_CANCEL
        end
    end
    API->>DB: outbox OrderCancelled, RefundRequested
```

| Người hủy | Thời điểm | Tiền thuê | Cọc | Tín hiệu |
|---|---|---|---|---|
| Renter | Trước khi thanh toán | Không có gì để hoàn | `VOID` | — |
| Renter | `start_at − now ≥ renter_free_cancel_minutes` (120') | Hoàn 100% | Hoàn 100% ngay | — |
| Renter | Muộn hơn (vẫn trước `start_at`) | Hoàn `late_cancel_refund_bps` (50%); phần còn lại thành doanh thu seller (trừ phí theo tỷ lệ) | Hoàn 100% ngay | — |
| Owner | Bất kỳ lúc nào trước `start_at` | Hoàn 100% | Hoàn 100% ngay | `OWNER_LATE_CANCEL` nếu `< owner_free_cancel_hours` (12h) |
| Hệ thống | Đến `start_at`, account chưa `AVAILABLE` | Hoàn 100% | Hoàn 100% | `OWNER_FAULT_NOT_READY` |
| Bất kỳ | Sau `IN_RENTAL` | Không hủy được, dùng dispute | — | — |

Ví dụ hủy muộn: rent 90.000, cọc 200.000, renter hủy trước giờ thuê 60 phút → hoàn 45.000 + 200.000 = 245.000; `kept = 45.000`, phí 4.500, seller nhận 40.500 vào review hold.

### 8.9. Hold doanh thu + rút tiền (UC-15, UC-36, UC-41)

```mermaid
sequenceDiagram
    autonumber
    participant J as Job releaseSellerHolds
    participant DB as PostgreSQL
    actor O as Owner
    participant API as RentGG API
    actor F as Finance
    J->>DB: SELECT orders co seller_hold_until nho hon hoac bang now, seller_released_at null
    J->>DB: Loai tru order co dispute renter dang OPEN
    J->>DB: ledger SELLER_HOLD_RELEASED, seller_released_at = now
    O->>API: POST /wallet/withdrawals (amount, bankAccount) + Idempotency-Key
    API->>DB: Kiem tra KYC VERIFIED, status ACTIVE, idempotency
    API->>DB: SELECT ledger_account SELLER_AVAILABLE cua owner FOR UPDATE
    API->>DB: Tinh available = -SUM(postings)
    alt amount lon hon available
        API-->>O: 422 INSUFFICIENT_AVAILABLE_BALANCE
    else Du
        API->>DB: INSERT withdrawal REQUESTED, ledger WITHDRAWAL_REQUESTED
        API-->>O: 201 withdrawal
    end
    F->>API: GET /finance/withdrawals?status=REQUESTED
    F->>F: Chuyen khoan thu cong theo lo
    alt Thanh cong
        F->>API: POST /finance/withdrawals/{id}/mark-paid (bankRef)
        API->>DB: withdrawal PAID, ledger WITHDRAWAL_PAID, audit
    else That bai
        F->>API: POST /finance/withdrawals/{id}/mark-failed (reason)
        API->>DB: withdrawal FAILED, ledger WITHDRAWAL_FAILED (tra ve available), audit
    end
```

- `seller_hold_until = actual_end_at + seller_hold_hours` (72h), hoặc `+ seller_hold_hours_high_risk` (168h) nếu seller `risk_tier = HIGH`.
- Dispute renter (`RENTER_LOGIN_FAILED`, `RENTER_NOT_AS_DESCRIBED`) mở khi doanh thu còn ở review hold → `SELLER_DISPUTE_HOLD` ngay; mở khi đơn còn `IN_RENTAL` → lúc `ORDER_ENDED` doanh thu vào review hold rồi chuyển ngay sang dispute hold trong cùng transaction.
- **Hai đồng hồ tách biệt:** hold review (tự động theo thời gian) và thời điểm chi trả thực tế (Finance `mark-paid` thủ công).
- Giải phóng hold sớm chỉ qua approval `SELLER_HOLD_EARLY_RELEASE`.

### 8.10. Dual approval (UC-42)

```mermaid
sequenceDiagram
    autonumber
    actor A1 as Staff yeu cau
    actor A2 as Staff duyet
    participant API as RentGG API
    participant DB as PostgreSQL
    participant T as Module dich
    A1->>API: POST /admin/approvals (actionType, payload, reasonCode)
    API->>DB: Validate payload theo actionType, INSERT approval PENDING, expires_at
    API->>DB: audit APPROVAL_REQUESTED
    A2->>API: POST /admin/approvals/{id}/approve
    API->>DB: SELECT approval FOR UPDATE
    alt A2 trung A1
        API-->>A2: 403 SELF_APPROVAL_FORBIDDEN
    else A2 khong du vai tro
        API-->>A2: 403 FORBIDDEN
    else Het han
        API->>DB: approval REJECTED APPROVAL_EXPIRED
        API-->>A2: 409 APPROVAL_EXPIRED
    else Hop le
        API->>DB: approval APPROVED, approved_by = A2
        API->>T: execute(payload, approvedBy = A2)
        T->>DB: Thay doi nghiep vu (vi du account AVAILABLE hoac ledger adjustment)
        API->>DB: approval EXECUTED, audit APPROVAL_EXECUTED
        API-->>A2: 200
    end
```

| `action_type` | Payload chính | Thực thi | Vai trò yêu cầu / duyệt |
|---|---|---|---|
| `GAME_ALLOW` | `gameId`, `tosUrl`, `reviewNote` | `games.tos_status = ALLOWED`, `tos_reviewed_at = now` | ADMIN / ADMIN khác |
| `ACCOUNT_SECURITY_RESTORE` | `gameAccountId`, `evidence` | `security_state SUSPENDED_SECURITY_REVIEW → AVAILABLE` (cần credential version mới) | SUPPORT hoặc ADMIN / ADMIN khác |
| `SELLER_HOLD_EARLY_RELEASE` | `orderId` | `SELLER_HOLD_RELEASED` trước hạn, nếu không có dispute renter mở | SUPPORT, FINANCE hoặc ADMIN / FINANCE hoặc ADMIN khác |
| `LEDGER_ADJUSTMENT` | `postings[] {accountCode, ownerUserId, amount}`, `orderId?` | `LEDGER_ADJUSTMENT` (tổng phải = 0) | FINANCE / FINANCE hoặc ADMIN khác |

---

## 9. Jobs định kỳ

Tất cả job chạy **mỗi phút** (`@Scheduled(cron = "0 * * * * *")`, có thể cấu hình), bọc `@SchedulerLock` (ShedLock JDBC) để chỉ một instance chạy tại một thời điểm. Mỗi job xử lý theo lô (mặc định 100 bản ghi), mỗi bản ghi trong **transaction riêng** để một lỗi không làm hỏng cả lô; chọn bản ghi bằng `FOR UPDATE SKIP LOCKED`; luôn kiểm tra lại trạng thái nguồn trong transaction (idempotent).

| Job | Chu kỳ | Điều kiện chọn bản ghi | Hành động | Idempotency | ShedLock (`lockAtMostFor` / `lockAtLeastFor`) |
|---|---|---|---|---|---|
| `expirePendingPayments` | 1 phút | `orders.status = 'PENDING_PAYMENT' AND payment_expires_at <= now` | Order `EXPIRED`; deposit `VOID`; intent `CREATED → EXPIRED`; outbox `OrderExpired` | Điều kiện trạng thái + `@Version`; chạy lại không còn bản ghi khớp | `PT5M` / `PT10S` |
| `expireOwnerApprovals` | 1 phút | `status = 'AWAITING_OWNER_APPROVAL' AND approval_expires_at <= now` | Order `REJECTED` (`APPROVAL_TIMEOUT`); `ORDER_CANCELLED_REFUND` 100%; deposit `REFUND_PENDING`; refunds; outbox `OrderRejected`, `RefundRequested` | Trạng thái + `idempotency_key` của ledger tx `ORDER_CANCELLED_REFUND:{orderId}` | `PT5M` / `PT10S` |
| `startDueOrders` | 1 phút | `status = 'CONFIRMED' AND start_at <= now` | Khóa GameAccount; `AVAILABLE` + game `ALLOWED` → `IN_RENTAL`, account `IN_USE`; ngược lại → `CANCELLED` (`OWNER_FAULT_NOT_READY` hoặc `PLATFORM_GAME_SUSPENDED`), hoàn 100%, tín hiệu | Trạng thái + `@Version` | `PT5M` / `PT10S` |
| `endDueOrders` | 1 phút | `status = 'IN_RENTAL' AND block_end_at <= now` | `ENDED`, `actual_end_at`; `ORDER_ENDED` (+ `SELLER_DISPUTE_HOLD` nếu có dispute renter mở); deposit `POST_RENTAL_HOLD`; `seller_hold_until`; account `ROTATION_PENDING`/`AWAITING_RECLAIM` | Trạng thái + ledger key `ORDER_ENDED:{orderId}` | `PT5M` / `PT10S` |
| `releaseSellerHolds` | 1 phút | `seller_hold_until <= now AND seller_released_at IS NULL` và không có dispute `RENTER_*` `OPEN` của order | `SELLER_HOLD_RELEASED` số tiền còn trong review hold của order; `seller_released_at = now` | Ledger key `SELLER_HOLD_RELEASED:{orderId}`; cột `seller_released_at` | `PT5M` / `PT10S` |
| `releaseDeposits` | 1 phút | `deposits.status = 'POST_RENTAL_HOLD' AND hold_until <= now` | `DEPOSIT_REFUND_REQUESTED`; deposit `REFUND_PENDING`; tạo `refunds` (`DEPOSIT`); outbox `RefundRequested` | Trạng thái + ledger key `DEPOSIT_REFUND_REQUESTED:{depositId}` | `PT5M` / `PT10S` |
| `markRotationOverdue` | 1 phút | `game_accounts.security_state = 'ROTATION_PENDING' AND rotation_due_at <= now` | `ROTATION_OVERDUE`; thông báo nhắc owner; `abuse_signals` `ROTATION_OVERDUE` | Trạng thái nguồn | `PT5M` / `PT10S` |
| `sendEndingReminders` | 1 phút | `status = 'IN_RENTAL' AND scheduled_end_at - interval '10 minutes' <= now AND ending_reminder_sent_at IS NULL` | Thông báo renter (sắp hết giờ, chế độ kết thúc, với GRACE: không bắt đầu trận mới) và owner (chuẩn bị đổi mật khẩu) | Cột `ending_reminder_sent_at` | `PT5M` / `PT10S` |
| `publishOutbox` | 1 phút (có thể 5–10 giây) | `outbox_events.published_at IS NULL ORDER BY created_at LIMIT 100` | Phát `ApplicationEvent`; thành công → `published_at = now`; lỗi → `attempts++`, `last_error`, thử lại với backoff | Consumer idempotent theo `event.id` | `PT2M` / `PT1S` |

Ghi chú:
- Thứ tự xử lý không phụ thuộc nhau: ví dụ `endDueOrders` của đơn trước và `startDueOrders` của đơn sau cùng phút thì đơn sau thấy account `ROTATION_PENDING` → hủy `OWNER_FAULT_NOT_READY`. Đây là hành vi đúng: chủ phải rotation xong trước giờ của đơn kế tiếp. Owner được cảnh báo khi nhận đơn sát nhau (UI nên khuyến nghị khoảng đệm ≥ `rotation_sla_minutes`).
- Mọi job dùng `Clock` bean; test integration tua `Clock` rồi gọi trực tiếp method của job.
- Metric cho mỗi job: số bản ghi xử lý, số lỗi, thời gian chạy (§16).

---

## 10. Outbox và sự kiện

### 10.1. Cơ chế

1. Service ghi thay đổi nghiệp vụ **và** một dòng `outbox_events` trong cùng transaction (`OutboxService.record(aggregateType, aggregateId, eventType, payload)`).
2. Job `publishOutbox` đọc các dòng chưa phát, chuyển thành `DomainEvent` và phát bằng `ApplicationEventPublisher`.
3. Consumer (`@EventListener`) chạy đồng bộ trong process; mỗi consumer **idempotent** (kiểm tra theo `event.id` hoặc theo trạng thái đích). Lỗi consumer → dòng outbox chưa được đánh dấu, thử lại lần sau (at-least-once).
4. Quá `max_attempts` (mặc định 10) → dừng thử, alert (`outbox_events_stuck`).
5. Payload không bao giờ chứa credentials, mật khẩu, số tài khoản ngân hàng đầy đủ.
6. Phase 2: thay publisher bằng relay đẩy ra Kafka; schema event giữ nguyên.

### 10.2. Danh mục event

| Event | Aggregate | Payload chính | Consumer |
|---|---|---|---|
| `OrderCreated` | Order | `orderId`, `listingId`, `gameAccountId`, `renterId`, `ownerId`, `startAt`, `blockEndAt`, `amountToPay` | notification |
| `OrderPaid` | Order | `orderId`, `intentId`, `rentAmount`, `depositAmount`, `nextStatus` | notification (renter, owner) |
| `OrderAwaitingApproval` | Order | `orderId`, `ownerId`, `approvalExpiresAt` | notification (owner) |
| `OrderConfirmed` | Order | `orderId`, `startAt`, `autoAccepted` | notification |
| `OrderRejected` | Order | `orderId`, `reason` (`OWNER_REJECTED`/`APPROVAL_TIMEOUT`) | notification |
| `OrderExpired` | Order | `orderId` | notification |
| `OrderStarted` | Order | `orderId`, `gameAccountId`, `blockEndAt`, `endMode` | notification (renter: credentials đã sẵn sàng) |
| `OrderEndingSoon` | Order | `orderId`, `scheduledEndAt`, `endMode` | notification |
| `OrderEnded` | Order | `orderId`, `actualEndAt`, `endMode`, `depositHoldUntil`, `sellerHoldUntil` | notification (owner: đổi mật khẩu / thu hồi) |
| `OrderCancelled` | Order | `orderId`, `cancelReason`, `cancelledBy`, `rentRefund`, `depositRefund` | notification |
| `CredentialViewed` | Order | `orderId`, `renterId`, `viewedAt`, `firstView` | notification (owner, lần đầu) |
| `HandoverConfirmed` | Order | `orderId`, `confirmedAt` | notification |
| `OtpRequested` | Order | `orderId`, `ownerId`, `requestedAt` | notification (owner, ưu tiên cao) |
| `GameAccountStateChanged` | GameAccount | `gameAccountId`, `from`, `to`, `reason` | notification (owner) |
| `RotationOverdue` | GameAccount | `gameAccountId`, `ownerId`, `dueAt`, `nextOrderStartAt` | notification |
| `CredentialRotated` | GameAccount | `gameAccountId`, `newVersion` | notification |
| `DepositStateChanged` | Deposit | `depositId`, `orderId`, `from`, `to`, `holdUntil` | notification (renter) |
| `RefundRequested` | Refund | `refundId`, `orderId`, `kind`, `amount`, `intentId` | payment (gọi `PaymentGateway.refund`) |
| `RefundCompleted` | Refund | `refundId`, `orderId`, `kind`, `amount` | deposit (→ `REFUNDED`), notification |
| `RefundFailed` | Refund | `refundId`, `error`, `attempts` | notification (Finance), alert |
| `DisputeOpened` | Dispute | `disputeId`, `orderId`, `type`, `openedBy`, `deadlineAt` | notification (bên kia, Support; `ACCOUNT_TAKEOVER` khẩn) |
| `DisputeEvidenceAdded` | Dispute | `disputeId`, `addedBy` | notification |
| `DisputeResolved` | Dispute | `disputeId`, `orderId`, `status`, `depositDeduction`, `rentRefund`, `reasonCode` | notification |
| `SellerHoldReleased` | Order | `orderId`, `sellerId`, `amount` | notification |
| `WithdrawalRequested` | Withdrawal | `withdrawalId`, `ownerId`, `amount` | notification (Finance) |
| `WithdrawalPaid` / `WithdrawalFailed` | Withdrawal | `withdrawalId`, `amount`, `reason?` | notification (owner) |
| `ListingStatusChanged` | Listing | `listingId`, `from`, `to`, `note` | notification (owner) |
| `GameTosStatusChanged` | Game | `gameId`, `from`, `to` | notification (owner có listing của game) |
| `ApprovalRequested` / `ApprovalDecided` | ApprovalRequest | `approvalId`, `actionType`, `status`, `actorId` | notification (staff) |
| `UserStatusChanged` | User | `userId`, `from`, `to`, `reason` | notification |

Audit **không** đi qua outbox: audit được ghi đồng bộ trong cùng transaction với thao tác (không được phép mất).

---

## 11. API

### 11.1. Quy ước chung

- Base path `/api/v1`, JSON UTF-8, thời gian ISO-8601 có offset (server trả UTC `Z`), tiền là số nguyên VND.
- Xác thực: `Authorization: Bearer <access JWT>` cho mọi endpoint trừ `auth/register`, `auth/login`, `auth/refresh`, `GET /games*`, `GET /listings/search`, `GET /listings/{id}`, webhook PSP và Swagger.
- **Idempotency-Key** (header, UUID do client sinh) **bắt buộc** cho `POST /orders`, `POST /orders/{id}/pay`, `POST /wallet/withdrawals`; khuyến nghị cho `POST /admin/approvals`. Cùng key + cùng payload → trả lại kết quả cũ; cùng key + payload khác → `409 IDEMPOTENCY_CONFLICT`. Thiếu key → `400 IDEMPOTENCY_KEY_REQUIRED`.
- Optimistic locking: thao tác sửa listing/duyệt đơn có thể trả `409 CONCURRENT_MODIFICATION`; client tải lại rồi thử lại.
- Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs`. Ở prod chỉ bật sau xác thực hoặc tắt.

### 11.2. Danh sách endpoint

Vai trò: `PUBLIC` (không cần đăng nhập), `USER` (đã đăng nhập; "renter"/"owner" là quan hệ với tài nguyên, kiểm tra theo dữ liệu), `SUPPORT`, `ADMIN`, `FINANCE`. Cột Idem.: **Bắt buộc** = phải có `Idempotency-Key`; "Tự nhiên" = idempotent theo trạng thái (gọi lại không gây tác dụng phụ thêm).

**Auth & người dùng**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/auth/register` | PUBLIC | Đăng ký (`email`, `password`, `displayName`, `dateOfBirth`); từ chối < 18 tuổi | — |
| POST | `/auth/login` | PUBLIC | Trả `accessToken` (15'), `refreshToken` (14 ngày); rate limit | — |
| POST | `/auth/refresh` | PUBLIC (refresh token) | Xoay refresh token, cấp access mới | — |
| GET | `/me` | USER | Hồ sơ, vai trò, `status`, `kycStatus`, `riskTier` | — |
| POST | `/me/kyc` | USER | Nộp KYC → `kyc_status = PENDING` | Tự nhiên |
| POST | `/admin/users/{id}/kyc` | ADMIN | `decision = VERIFY/REJECT`, `note` | Tự nhiên |
| POST | `/admin/users/{id}/status` | ADMIN, SUPPORT | Đổi `ACTIVE/RESTRICTED/SUSPENDED` với `reasonCode` | Tự nhiên |

**Catalog**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| GET | `/games` | PUBLIC | Danh sách game (mặc định chỉ `ALLOWED`; admin xem tất cả) | — |
| GET | `/games/{id}` | PUBLIC | Chi tiết + tham số chính sách công khai (cọc min/max, hold, hủy, complaint window) | — |
| POST | `/admin/games` | ADMIN | Tạo game (mặc định `tos_status = REVIEW_REQUIRED`) | — |
| PUT | `/admin/games/{id}` | ADMIN | Sửa tham số chính sách (chỉ áp dụng cho đơn mới) | — |
| POST | `/admin/games/{id}/tos-status` | ADMIN | Đổi `tos_status`; sang `ALLOWED` từ `PROHIBITED`/`SUSPENDED` → tạo `ApprovalRequest GAME_ALLOW`, trả `202` | Tự nhiên |

**GameAccount**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/game-accounts` | USER (owner, KYC `VERIFIED`) | Tạo account: `gameId`, `serverRegion`, `username`, `password`, `recoveryEmailOwned`, `recoveryPhoneOwned`, `twoFactorOwned` (đều phải `true`) → `PENDING_VERIFICATION` | — |
| GET | `/game-accounts` | USER (owner) | Danh sách account của tôi (`loginHint`, `securityState`, không có mật khẩu) | — |
| POST | `/game-accounts/{id}/rotation` | USER (owner) | `newPassword`, `newUsername?`, `loggedOutAllDevices = true` → credential version mới; `ROTATION_PENDING/OVERDUE → AVAILABLE` | Tự nhiên |
| POST | `/game-accounts/{id}/reclaim` | USER (owner) | `AWAITING_RECLAIM → ROTATION_PENDING` | Tự nhiên |
| POST | `/admin/game-accounts/{id}/verify-recovery` | ADMIN | Xác minh chủ nắm thông tin khôi phục → `AVAILABLE` | Tự nhiên |

**Listing**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/listings` | USER (owner, KYC `VERIFIED`) | Tạo `DRAFT`: `gameAccountId`, `title`, `description`, `attributes`, `pricePerHour`, `depositAmount`, `autoAccept`, `endMode` | — |
| PUT | `/listings/{id}` | USER (owner) | Sửa (kèm `version`); `ACTIVE` sửa tiêu đề/mô tả/thuộc tính → `PENDING_REVIEW`; đổi giá/cọc/auto_accept/end_mode áp dụng ngay (audit `LISTING_TERMS_CHANGED`) | — |
| POST | `/listings/{id}/submit` | USER (owner) | `DRAFT/PAUSED → PENDING_REVIEW` | Tự nhiên |
| POST | `/listings/{id}/pause` | USER (owner) | `ACTIVE → PAUSED` | Tự nhiên |
| GET | `/listings/search` | PUBLIC | `gameId`, `minPrice`, `maxPrice`, `from`, `to` (khung giờ phải trống), phân trang | — |
| GET | `/listings/{id}` | PUBLIC | Chi tiết listing + chính sách + lịch đã bận (chỉ khoảng thời gian) | — |
| POST | `/admin/listings/{id}/approve` | ADMIN | `PENDING_REVIEW → ACTIVE` | Tự nhiên |
| POST | `/admin/listings/{id}/reject` | ADMIN | `→ REJECTED` + `note` | Tự nhiên |
| POST | `/admin/listings/{id}/suspend` | ADMIN | `→ SUSPENDED` + `reasonCode` | Tự nhiên |

**Order**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/orders` | USER (renter) | `listingId`, `startAt`, `durationMinutes` → `PENDING_PAYMENT` + deposit; trả `amountToPay`, `paymentExpiresAt` | **Bắt buộc** |
| POST | `/orders/{id}/pay` | USER (renter của đơn) | Tạo `PaymentIntent`, trả `checkoutUrl` | **Bắt buộc** |
| GET | `/orders/{id}` | USER (renter/owner của đơn), SUPPORT, ADMIN | Chi tiết đơn, deposit, timeline (không có credentials) | — |
| GET | `/orders` | USER | `role = RENTER/OWNER`, `status`, phân trang | — |
| POST | `/orders/{id}/approve` | USER (owner của đơn) | `AWAITING_OWNER_APPROVAL → CONFIRMED` | Tự nhiên |
| POST | `/orders/{id}/reject` | USER (owner của đơn) | `→ REJECTED`, hoàn 100% | Tự nhiên |
| POST | `/orders/{id}/cancel` | USER (renter/owner của đơn) | Hủy trước `start_at` theo chính sách (§8.8) | Tự nhiên |
| GET | `/orders/{id}/credentials` | USER (renter của đơn) | Credentials trong cửa sổ hợp lệ; rate limit 10/giờ/order; audit | — |
| POST | `/orders/{id}/handover/confirm` | USER (renter của đơn) | Xác nhận đăng nhập thành công | Tự nhiên |
| POST | `/orders/{id}/otp-request` | USER (renter của đơn) | Yêu cầu chủ gửi OTP (game `OWNER_ASSISTED_OTP`) | — (rate limit) |

**Payment**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/payments/webhook/{provider}` | PSP (HMAC) | Header `X-Signature: hex(HMAC-SHA256(secret, rawBody))`; xử lý kết quả thanh toán/hoàn tiền | Theo `(provider, provider_ref)` |
| POST | `/dev/payments/{intentId}/simulate-success` | USER (chỉ profile `dev`) | Giả lập webhook thành công | Theo intent |

**Dispute**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/orders/{id}/disputes` | USER (renter/owner của đơn) | `type`, `description`, `evidenceUrls`; kiểm tra loại × thời hạn (§6.5) | 1 dispute `OPEN` / loại / đơn |
| GET | `/disputes/{id}` | USER (hai bên), SUPPORT, ADMIN | Chi tiết, bằng chứng, kết quả | — |
| POST | `/disputes/{id}/evidence` | USER (hai bên) | Thêm URL bằng chứng + ghi chú | — |
| GET | `/support/disputes` | SUPPORT, ADMIN | Lọc `status`, `type`, sắp theo `deadline_at`, phân trang | — |
| POST | `/support/disputes/{id}/resolve` | SUPPORT, ADMIN | `decision = RESOLVE/REJECT`, `depositDeduction`, `rentRefund`, `reasonCode`, `renterStatus?` | Tự nhiên |

**Wallet & Finance**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| GET | `/wallet/balances` | USER | `reviewHold`, `disputeHold`, `available`, `payoutProcessing` | — |
| GET | `/wallet/deposits` | USER | Cọc của tôi: `orderId`, `amount`, `status`, `holdUntil`, `deductedAmount`, `refundedAmount` | — |
| POST | `/wallet/withdrawals` | USER (owner, KYC `VERIFIED`) | `amount`, `bankName`, `bankAccountNumber`, `bankAccountName` | **Bắt buộc** |
| GET | `/finance/withdrawals` | FINANCE, ADMIN | Lọc `status`, phân trang; xuất lô | — |
| POST | `/finance/withdrawals/{id}/mark-paid` | FINANCE | `bankRef` → `PAID` | Tự nhiên |
| POST | `/finance/withdrawals/{id}/mark-failed` | FINANCE | `reason` → `FAILED`, trả về available | Tự nhiên |

**Approval & Notification**

| Method | Path | Vai trò | Mô tả | Idem. |
|---|---|---|---|---|
| POST | `/admin/approvals` | SUPPORT, ADMIN, FINANCE | Tạo yêu cầu (`actionType`, `payload`, `reasonCode`) | Khuyến nghị |
| GET | `/admin/approvals` | SUPPORT, ADMIN, FINANCE | Lọc `status`, `actionType` | — |
| POST | `/admin/approvals/{id}/approve` | Theo bảng §8.10 (≠ người yêu cầu) | Duyệt và thực thi | Tự nhiên |
| POST | `/admin/approvals/{id}/reject` | Theo bảng §8.10 (≠ người yêu cầu) | Từ chối | Tự nhiên |
| GET | `/notifications` | USER | Thông báo in-app, phân trang, lọc `unread` | — |

### 11.3. Quy ước lỗi

Mọi lỗi trả JSON thống nhất (do `GlobalExceptionHandler` sinh):

```json
{
  "code": "SLOT_UNAVAILABLE",
  "message": "Khung giờ đã có người đặt cho account này.",
  "details": {
    "gameAccountId": "0192f0c1-7a3e-7c55-9d8e-3b1f2a6c4d10",
    "startAt": "2026-11-01T12:00:00Z",
    "endAt": "2026-11-01T15:00:00Z"
  },
  "traceId": "6f1c2a9e0b7d4e1f"
}
```

| HTTP | `code` tiêu biểu | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` (`details.fields[]`), `IDEMPOTENCY_KEY_REQUIRED`, `MALFORMED_REQUEST` | Sai định dạng, thiếu trường, vi phạm Bean Validation |
| 401 | `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `INVALID_CREDENTIALS`, `WEBHOOK_SIGNATURE_INVALID` | Thiếu/hết hạn JWT, sai mật khẩu, chữ ký webhook sai |
| 403 | `FORBIDDEN`, `NOT_RESOURCE_OWNER`, `USER_RESTRICTED`, `USER_SUSPENDED`, `KYC_REQUIRED`, `AGE_RESTRICTED`, `SELF_APPROVAL_FORBIDDEN` | Sai vai trò, không phải renter/owner của đơn, bị hạn chế |
| 404 | `NOT_FOUND` | Không tồn tại **hoặc** người gọi không có quyền biết nó tồn tại (ví dụ credentials của đơn người khác) |
| 409 | `SLOT_UNAVAILABLE`, `ACCOUNT_NOT_AVAILABLE`, `INVALID_STATE`, `CONCURRENT_MODIFICATION`, `IDEMPOTENCY_CONFLICT`, `LISTING_ALREADY_ACTIVE`, `DISPUTE_ALREADY_OPEN`, `APPROVAL_EXPIRED` | Xung đột trạng thái/đồng thời |
| 422 | `DEPOSIT_OUT_OF_RANGE`, `DURATION_OUT_OF_RANGE`, `START_IN_PAST`, `GAME_NOT_ALLOWED`, `AUTO_ACCEPT_NOT_ALLOWED`, `RECOVERY_ATTESTATION_REQUIRED`, `CREDENTIAL_WINDOW_CLOSED`, `DISPUTE_WINDOW_CLOSED`, `COMPLAINT_WINDOW_CLOSED`, `INSUFFICIENT_AVAILABLE_BALANCE`, `DEDUCTION_EXCEEDS_DEPOSIT`, `CANNOT_RENT_OWN_LISTING` | Hợp lệ về cú pháp nhưng vi phạm quy tắc nghiệp vụ |
| 429 | `RATE_LIMITED` (+ header `Retry-After`) | Vượt rate limit login/credentials/OTP |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước; không lộ stack trace, chỉ `traceId` |

Ánh xạ lỗi DB: `23P01` (exclusion violation trên `ex_orders_no_overlap`) → `409 SLOT_UNAVAILABLE`; `23505` trên `ux_orders_idem`/`ux_withdrawals_idem` → đọc lại bản ghi cũ (idempotent replay); `23505` trên `ux_listings_one_active_per_account` → `409 LISTING_ALREADY_ACTIVE`; `OptimisticLockException` → `409 CONCURRENT_MODIFICATION`.

### 11.4. Phân trang

- Query: `page` (bắt đầu 0), `size` (mặc định 20, tối đa 100), `sort` (ví dụ `createdAt,desc`; chỉ cho phép danh sách trường trắng).
- Response:

```json
{
  "content": [ { "id": "…" } ],
  "page": 0,
  "size": 20,
  "totalElements": 134,
  "totalPages": 7
}
```

### 11.5. Ví dụ request/response

```http
POST /api/v1/orders
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Idempotency-Key: 7d8c2f1e-4b6a-4e0e-9a51-2f3c8d9b1a77
Content-Type: application/json

{ "listingId": "0192f0c1-1111-7000-8000-000000000001",
  "startAt": "2026-11-01T12:00:00Z",
  "durationMinutes": 180 }
```

```json
HTTP/1.1 201 Created
{
  "id": "0192f0c1-2222-7000-8000-000000000002",
  "status": "PENDING_PAYMENT",
  "startAt": "2026-11-01T12:00:00Z",
  "scheduledEndAt": "2026-11-01T15:00:00Z",
  "blockEndAt": "2026-11-01T15:00:00Z",
  "rentAmount": 90000,
  "depositAmount": 200000,
  "amountToPay": 290000,
  "paymentExpiresAt": "2026-10-26T08:15:00Z",
  "endMode": "FORCE_AT_END"
}
```

---

## 12. Bảo mật

### 12.1. Xác thực

- Mật khẩu RentGG băm **BCrypt** (cost 12). Không có giới hạn ký tự đặc biệt; tối thiểu 10 ký tự.
- **Access token** JWT HS256 (Nimbus), sống 15 phút, claim: `sub` (userId), `roles`, `status`, `iat`, `exp`, `jti`. Secret ≥ 256 bit từ `RENTGG_JWT_SECRET`.
- **Refresh token** ngẫu nhiên 256 bit, sống 14 ngày, chỉ lưu `SHA-256` trong `refresh_tokens`; xoay vòng mỗi lần dùng; phát hiện dùng lại token đã thu hồi → thu hồi cả họ token.
- Người dùng `SUSPENDED` không đăng nhập/refresh được; đổi status sang `SUSPENDED` thu hồi mọi refresh token. Access token còn hạn tối đa 15 phút — chấp nhận ở MVP; các endpoint nhạy cảm (credentials, rút tiền, tạo đơn) đọc lại `users.status` từ DB thay vì tin claim.
- Phase 1: MFA (TOTP) bắt buộc cho SUPPORT/ADMIN/FINANCE.

### 12.2. Ủy quyền theo vai trò và theo tài nguyên

| Vai trò | Được làm | Không được làm |
|---|---|---|
| `USER` | Thuê, đăng listing (khi KYC), quản lý account/listing/đơn **của mình**, dispute đơn của mình, rút tiền của mình | Xem đơn/credentials của người khác, mọi endpoint `/admin`, `/support`, `/finance` |
| `SUPPORT` | Xem đơn/dispute (metadata), phân xử dispute, đổi status user (với reason), tạo approval | Xem mật khẩu, sửa ledger trực tiếp, tự duyệt approval |
| `ADMIN` | Quản lý game, duyệt listing, xác minh recovery, KYC, duyệt approval | Xem mật khẩu, tự duyệt, chỉnh ledger ngoài approval |
| `FINANCE` | Danh sách rút tiền, mark-paid/failed, tạo/duyệt approval tài chính | Xem mật khẩu, phân xử dispute, tự duyệt |

- Kiểm tra vai trò bằng `@PreAuthorize` / cấu hình `SecurityFilterChain` theo prefix (`/admin/**` → ADMIN/SUPPORT/FINANCE theo endpoint, `/support/**` → SUPPORT/ADMIN, `/finance/**` → FINANCE/ADMIN).
- Kiểm tra **quyền theo tài nguyên** trong application service (renter/owner của đơn, owner của account/listing). Truy cập tài nguyên không thuộc mình → `404` cho dữ liệu nhạy cảm (credentials, đơn), `403` cho thao tác trên tài nguyên công khai.

### 12.3. Mã hóa credentials

- Thuật toán **AES-256-GCM**, IV 12 byte ngẫu nhiên (`SecureRandom`) cho **mỗi** lần mã hóa, tag 128 bit. Plaintext là JSON `{"username": "...", "password": "..."}`.
- AAD (additional authenticated data) = `game_account_id || version` để chống hoán đổi ciphertext giữa các bản ghi.
- Lưu `ciphertext`, `iv`, `key_id` trong `credential_versions`. Khóa **không** nằm trong DB: lấy từ `RENTGG_CREDENTIAL_KEYS` (danh sách `keyId:base64Key`) và `RENTGG_CREDENTIAL_ACTIVE_KEY_ID`.
- **Xoay khóa:** thêm khóa mới vào danh sách, đổi `ACTIVE_KEY_ID` → bản ghi mới dùng khóa mới; bản ghi cũ vẫn giải mã được theo `key_id`. Job/command re-encrypt (chạy tay) mã hóa lại các bản `ACTIVE` sang khóa mới rồi gỡ khóa cũ. Mỗi rotation mật khẩu cũng tự nhiên tạo ciphertext bằng khóa hiện hành.
- **Envelope-ready:** Phase 2 thay `CredentialCipher` bằng triển khai KMS (DEK theo bản ghi, mã hóa DEK bằng KEK trong KMS/Vault) mà không đổi schema (`key_id` trỏ tới KEK, thêm cột `encrypted_dek`).
- Mật khẩu chỉ tồn tại plaintext trong bộ nhớ khi mã hóa (tạo/rotation) và khi giải mã cho renter hợp lệ. DTO chứa mật khẩu là `record` có `toString()` che (`password=***`); không log request/response body của các endpoint `/game-accounts` và `/orders/{id}/credentials`; Jackson không serialize vào log.
- Bản `RETIRED` vẫn lưu ciphertext (phục vụ phân xử, ví dụ chứng minh renter đã nhận đúng mật khẩu) nhưng **không có** đường đọc qua API.

### 12.4. Quy tắc xem credentials

`GET /orders/{id}/credentials` chỉ trả dữ liệu khi **tất cả** điều kiện sau đúng:

1. JWT hợp lệ và `users.status` (đọc từ DB) khác `SUSPENDED`. (`RESTRICTED` chỉ chặn tạo đơn mới; renter bị `RESTRICTED` vì takeover thì đơn đã `ENDED` nên điều kiện 3 tự chặn.)
2. Người gọi là `renter_id` của đơn.
3. `orders.status = IN_RENTAL`.
4. `start_at ≤ now ≤ block_end_at`.
5. GameAccount ở `IN_USE`.
6. Chưa vượt rate limit 10 lần/giờ/order (Bucket4j, key `credentials:{orderId}`).

Mỗi lần trả: audit `CREDENTIAL_VIEWED` (actor, orderId, credentialVersion, ip, user-agent; **không** ghi mật khẩu); lần đầu ghi `credential_first_viewed_at` và phát `CredentialViewed`; header `Cache-Control: no-store`, `Pragma: no-cache`. Hợp đồng cho phép yêu cầu re-auth bằng mật khẩu RentGG; MVP dùng JWT + rate limit + audit, re-auth là cấu hình có thể bật (`rentgg.credentials.require-reauth`).

Không có endpoint nào cho SUPPORT/ADMIN/FINANCE xem mật khẩu, kể cả khi phân xử.

### 12.5. Rate limit

| Đối tượng | Key | Giới hạn mặc định |
|---|---|---|
| `POST /auth/login` | IP + email | 5 lần/phút, 20 lần/giờ; sai liên tiếp → chờ lũy tiến |
| `POST /auth/register` | IP | 10 lần/giờ |
| `GET /orders/{id}/credentials` | orderId | 10 lần/giờ |
| `POST /orders/{id}/otp-request` | orderId | 5 lần/giờ |
| `POST /orders` | userId | 30 lần/giờ |

Bucket4j in-memory: với nhiều instance, giới hạn thực tế = giới hạn × số instance (chấp nhận ở MVP; Redis-backed ở Phase 2).

### 12.6. Webhook PSP

- Header `X-Signature = hex(HMAC-SHA256(webhookSecret[provider], rawBody))`; so sánh constant-time (`MessageDigest.isEqual`).
- Xác minh trên **raw body** trước khi parse JSON. Sai chữ ký → `401`, audit, metric `webhook_signature_invalid_total`.
- Idempotent theo `(provider, provider_ref)` + trạng thái intent; số tiền phải khớp intent.
- Có thể thêm timestamp trong payload và từ chối sự kiện cũ hơn 10 phút (replay window) khi PSP hỗ trợ.

### 12.7. Audit

- Bảng `audit_logs` append-only (trigger chặn UPDATE/DELETE/TRUNCATE).
- Bắt buộc audit: xem credentials; tạo/rotation credentials (không ghi giá trị); xác minh recovery; duyệt/từ chối/đình chỉ listing; đổi `tos_status`; KYC; đổi status user; mở/phân xử dispute; mark-paid/failed rút tiền; mọi bước approval; đăng nhập staff; webhook sai chữ ký.
- Ghi đồng bộ trong cùng transaction với thao tác.

### 12.8. Dual approval

- 4 loại hành động: `GAME_ALLOW`, `ACCOUNT_SECURITY_RESTORE`, `SELLER_HOLD_EARLY_RELEASE`, `LEDGER_ADJUSTMENT` (§8.10).
- Người duyệt ≠ người yêu cầu (kiểm tra ở service + `CHECK` DB). Approval có `expires_at`; thực thi trong cùng transaction với `APPROVED → EXECUTED`.

### 12.9. Danh sách "không được override"

Không vai trò nào, kể cả qua approval, được phép:

1. Xem hoặc xuất mật khẩu/credentials của bất kỳ account nào.
2. Sửa hoặc xóa bản ghi ledger, audit (chỉ ghi bút toán bù `LEDGER_ADJUSTMENT` qua dual approval).
3. Tự duyệt yêu cầu của chính mình.
4. Tạo đơn chồng lấn khung giờ (exclusion constraint không có ngoại lệ).
5. Dùng lại/chuyển cọc giữa các đơn, khấu trừ vượt `deposits.amount` của đơn.
6. Cho phép rút tiền vượt `SELLER_AVAILABLE`.
7. Kích hoạt listing của game không `ALLOWED` hoặc account chưa xác minh recovery.
8. Vượt qua MFA/OTP hoặc cơ chế bảo mật của nhà phát hành; đăng nhập hộ vào account của người dùng.
9. Cho người dùng dưới 18 tuổi giao dịch.
10. Bật `auto_accept` cho game `OWNER_ASSISTED_OTP`.

### 12.10. Bảo mật khác

- TLS bắt buộc ở reverse proxy; HSTS; CORS chỉ cho origin FE đã cấu hình.
- Bean Validation cho mọi DTO; giới hạn kích thước body (1 MB); `evidence_urls` chỉ chấp nhận `https://`, tối đa 20 URL.
- Không trả stack trace; lỗi 500 chỉ có `traceId`.
- Phụ thuộc được quét lỗ hổng (OWASP dependency-check hoặc tương đương) trong CI.
- Thông tin ngân hàng trong `bank_snapshot` trả về API dạng che (4 số cuối) trừ cho FINANCE.

---

## 13. Threat model

Phương pháp: STRIDE cho hai luồng tài sản quan trọng nhất (credentials và tiền), sau đó là các kịch bản lạm dụng cụ thể. Mức rủi ro còn lại: Thấp / Trung bình / Cao sau khi áp dụng biện pháp.

### 13.1. Tài sản và ranh giới tin cậy

- Tài sản: credentials account game; thông tin khôi phục (chỉ chủ giữ, không vào hệ thống); tiền (thuê, cọc, doanh thu, rút); dữ liệu cá nhân (email, ngày sinh, KYC, ngân hàng); tính toàn vẹn ledger/audit.
- Ranh giới: Internet ↔ API; API ↔ PostgreSQL; API ↔ PSP (ra và webhook vào); người dùng ↔ hệ thống nhà phát hành (ngoài kiểm soát RentGG); nhân viên ↔ công cụ admin.

### 13.2. STRIDE — luồng credentials

| STRIDE | Mối đe dọa | Tác nhân | Tác động | Biện pháp giảm thiểu | Rủi ro còn lại |
|---|---|---|---|---|---|
| S — Spoofing | Kẻ gian dùng JWT đánh cắp để lấy credentials | Kẻ tấn công ngoài | Lộ mật khẩu, chiếm account | Access token 15'; refresh xoay vòng + phát hiện reuse; chỉ renter của đơn; cửa sổ thời gian; rate limit; audit IP/UA; tùy chọn re-auth | Trung bình |
| S | Người dùng khác đoán `orderId` để xem | User khác | Lộ mật khẩu | UUID ngẫu nhiên; kiểm tra `renter_id`; trả `404` | Thấp |
| T — Tampering | Hoán đổi ciphertext giữa các account trong DB | Người có quyền ghi DB | Renter nhận sai account | AES-GCM có tag + AAD = `game_account_id‖version` | Thấp |
| T | Owner cập nhật mật khẩu sai khi rotation | Owner | Renter kế tiếp không đăng nhập được | Complaint window → `RENTER_LOGIN_FAILED` → hoàn tiền + tín hiệu owner | Trung bình |
| R — Repudiation | Renter chối đã xem credentials / owner chối đã đưa đúng | Renter / Owner | Tranh chấp không phân xử được | Audit mỗi lần xem kèm `credentialVersion`; `credential_first_viewed_at`; bản `RETIRED` còn lưu để đối chiếu; `handover_confirmed_at` | Thấp |
| I — Information disclosure | Mật khẩu lộ qua log, APM, exception, cache trình duyệt | Lỗi lập trình, người đọc log | Lộ hàng loạt | DTO che `toString`; không log body endpoint nhạy cảm; `no-store`; review/test kiểm tra log không chứa chuỗi mật khẩu | Thấp |
| I | Lộ dump DB / backup | Kẻ tấn công, nhân viên | Lộ credentials | Mã hóa AES-256-GCM, khóa ngoài DB; backup mã hóa; user DB tối thiểu quyền | Thấp (Trung bình nếu khóa cũng lộ) |
| I | Nhân viên xem mật khẩu | Staff lạm quyền | Chiếm account | Không có endpoint; không có quyền đọc khóa ở công cụ admin; audit | Thấp |
| I | Renter giữ mật khẩu để dùng sau khi hết đơn | Renter | Dùng trái phép | Rotation bắt buộc sau mỗi đơn; account không nhận đơn khi chưa rotation; `password_change_kicks_sessions` | Trung bình (phụ thuộc chủ đổi mật khẩu và hành vi game) |
| D — Denial of service | Spam endpoint credentials/OTP | Renter / bot | Làm phiền owner, tải hệ thống | Rate limit theo order; OTP 5/giờ | Thấp |
| E — Elevation of privilege | Renter đổi mật khẩu/email khôi phục để chiếm account | Renter | Mất account | R2 (chủ giữ email/SĐT/2FA nên lấy lại được); cọc; `ACCOUNT_TAKEOVER` khẩn → `RESTRICTED`; tín hiệu lạm dụng | Trung bình |

### 13.3. STRIDE — luồng tiền

| STRIDE | Mối đe dọa | Tác nhân | Tác động | Biện pháp giảm thiểu | Rủi ro còn lại |
|---|---|---|---|---|---|
| S | Webhook giả báo thanh toán thành công | Kẻ tấn công | Đơn được xác nhận không trả tiền | HMAC-SHA256 trên raw body, so sánh constant-time; số tiền phải khớp; đối soát PSP | Thấp |
| S | Giả danh owner để rút tiền | Kẻ chiếm tài khoản RentGG | Mất doanh thu | KYC; tài khoản ngân hàng snapshot; Finance kiểm tra tên chủ TK khớp KYC; thông báo mỗi yêu cầu rút; Phase 1: MFA khi rút | Trung bình |
| T | Sửa ledger để tăng số dư | Staff / kẻ có quyền DB | Thất thoát | Trigger append-only; constraint cân bằng; điều chỉnh chỉ qua dual approval; đối soát | Thấp |
| T | Thay đổi số tiền trên request tạo đơn | Renter | Trả thiếu | Giá tính phía server từ listing; client không gửi số tiền | Thấp |
| R | Finance chối đã chi / chưa chi | Finance | Tranh chấp với seller | `mark-paid` cần `bankRef`; audit; ledger `WITHDRAWAL_PAID` | Thấp |
| I | Lộ thông tin ngân hàng seller | Người dùng khác, staff | Riêng tư | Che số TK trong API; chỉ FINANCE xem đầy đủ; audit | Thấp |
| D | Tạo nhiều đơn `PENDING_PAYMENT` để chiếm lịch | Renter xấu / đối thủ | Account không cho thuê được | Giữ chỗ chỉ 15 phút; rate limit tạo đơn; tín hiệu lạm dụng với tỷ lệ hết hạn cao | Trung bình |
| E | Rút tiền trùng / vượt số dư bằng request đồng thời | Owner | Thất thoát | `SELECT FOR UPDATE` trên ledger account + Idempotency-Key unique | Thấp |
| E | Tự duyệt giải phóng hold / điều chỉnh ledger | Staff | Thất thoát | Dual approval + `CHECK approved_by <> requested_by` | Thấp |
| E | Dùng lại cọc đơn cũ để thuê đơn mới | Renter | Chủ account mới không được bảo vệ | Cọc 1–1 với order (`UNIQUE`), không có API dùng lại; payment intent luôn gồm cọc mới | Thấp |

### 13.4. Kịch bản lạm dụng cụ thể

| # | Kịch bản | Cách thực hiện | Phát hiện | Phản ứng của hệ thống | Rủi ro còn lại |
|---|---|---|---|---|---|
| 1 | **Renter chiếm account** (UC-43) | Trong lúc thuê đổi mật khẩu, email/SĐT khôi phục, bật 2FA của mình | Owner phát hiện (không đăng nhập được, nhận email cảnh báo từ game) | Owner dùng thông tin khôi phục mình giữ (R2) để lấy lại; mở `ACCOUNT_TAKEOVER` → đơn `ENDED`, cọc `DISPUTED`, account `SUSPENDED_SECURITY_REVIEW`, renter `RESTRICTED`; Support khấu trừ cọc tới 100%; tín hiệu `RENTER_TAKEOVER_CONFIRMED`; khôi phục account qua dual approval | Trung bình: thiệt hại vượt cọc xử lý ngoài nền tảng; mức cọc phải đủ răn đe (quyết định còn mở) |
| 2 | **Chủ đưa mật khẩu sai** | Nhập sai khi tạo/rotation (cố ý để lấy phí hủy muộn hoặc vô ý) | Renter mở `RENTER_LOGIN_FAILED` trong complaint window | Doanh thu giữ lại (escrow/dispute hold), Support hoàn 100% tiền thuê + cọc hoàn bình thường; tín hiệu `OWNER_LOGIN_FAILED_CONFIRMED`; tái phạm → `risk_tier = HIGH` (hold 168h), đình chỉ listing | Thấp |
| 3 | **Chủ không đổi mật khẩu** sau đơn | Bỏ qua `ROTATION_PENDING` | Job `markRotationOverdue`; đơn kế đến giờ mà account chưa `AVAILABLE` | `ROTATION_OVERDUE` + nhắc; đơn kế bị hủy `OWNER_FAULT_NOT_READY`, hoàn 100%; tín hiệu; account không nhận đơn mới đến khi rotation | Thấp với nền tảng; chủ tự chịu rủi ro renter cũ còn mật khẩu |
| 4 | **Chủ báo thiệt hại giả** | Mở `OWNER_DAMAGE` không có căn cứ để lấy cọc | Support xem bằng chứng hai bên, lịch sử dispute của owner | Bằng chứng trước khi phạt; renter được phản hồi; bác bỏ → hoàn đủ cọc; tín hiệu `FALSE_CLAIM` cho owner; tái phạm → hạn chế | Trung bình (phụ thuộc chất lượng bằng chứng; Phase 1: chuẩn hóa ảnh trước/sau) |
| 5 | **Renter khiếu nại giả** | Mở `RENTER_LOGIN_FAILED`/`NOT_AS_DESCRIBED` dù đã chơi | `CREDENTIAL_VIEWED` audit, `handover_confirmed_at`, lịch sử renter, owner cung cấp lịch sử đăng nhập game | Chỉ mở trong complaint window, trước khi xác nhận bàn giao; Support bác bỏ → doanh thu trả về hold bình thường; tín hiệu `FALSE_CLAIM` | Trung bình |
| 6 | **Nhân viên lạm quyền** | Xem mật khẩu, tự giải phóng tiền, sửa sổ, xử dispute thiên vị | Audit, review định kỳ, metric override rate | Không có endpoint xem mật khẩu; dual approval cho thao tác tài chính/bảo mật; ledger/audit append-only; vai trò tối thiểu; Phase 1 MFA | Thấp–Trung bình (thông đồng 2 người) |
| 7 | **Webhook giả** | Gửi POST tới `/payments/webhook/{provider}` | Chữ ký sai, metric | `401`; không đổi trạng thái; số tiền phải khớp; đối soát với báo cáo PSP | Thấp (nếu lộ secret: xoay secret) |
| 8 | **Đặt trùng** | Hai renter (hoặc script) đặt cùng khung giờ đồng thời | Lỗi `23P01` | Exclusion constraint chỉ cho một đơn commit; bên kia `409 SLOT_UNAVAILABLE` | Rất thấp |
| 9 | **Rút tiền trùng** | Gửi nhiều request rút song song / bấm nhiều lần | Idempotency, row lock | Cùng key → trả kết quả cũ; khác key → tuần tự hóa bởi `FOR UPDATE`, request sau thấy available đã giảm → `422` | Rất thấp |
| 10 | **Lộ DB** | SQL injection, dump backup, truy cập trái phép | IDS, audit DB | JPA/parameterized query; credentials mã hóa với khóa ngoài DB; BCrypt mật khẩu; refresh token lưu hash; backup mã hóa; quyền DB tối thiểu. Ứng phó: xoay khóa credential, yêu cầu mọi chủ rotation, thu hồi mọi refresh token, xoay JWT/webhook secret | Trung bình nếu khóa và DB cùng lộ (giảm bằng KMS ở Phase 2) |
| 11 | Chiếm lịch bằng đơn chưa thanh toán | Tạo nhiều đơn `PENDING_PAYMENT` | Tỷ lệ hết hạn theo user | Giữ chỗ 15', rate limit 30 đơn/giờ, tín hiệu | Thấp |
| 12 | Owner hủy muộn hàng loạt | Nhận đơn rồi hủy sát giờ | `OWNER_LATE_CANCEL` | Hoàn 100% cho renter; tín hiệu; giảm hiển thị/đình chỉ (UC-14 Phase 1) | Thấp |
| 13 | Người dưới 18 tuổi | Khai ngày sinh giả | KYC (với owner) | Chặn < 18 khi đăng ký và khi giao dịch; KYC bắt buộc với owner; renter MVP dựa vào khai báo (rủi ro được chấp nhận, xem lại Phase 1) | Trung bình |

---

## 14. Cấu hình

### 14.1. Tham số theo game (bảng `games`)

| Tham số | Mặc định | Ràng buộc | Ghi chú |
|---|---|---|---|
| `tos_status` | `REVIEW_REQUIRED` | enum | Fail-closed: game mới không cho đăng listing |
| `handover_mode` | `STATIC_CREDENTIAL` | enum | Theo checklist A |
| `password_change_kicks_sessions` | `false` (bảo thủ đến khi kiểm chứng) | bool | Theo checklist A |
| `deposit_min` | Bắt buộc nhập (ví dụ 50.000) | `> 0` | Quyết định còn mở: mức cọc thực tế |
| `deposit_max` | Bắt buộc nhập (ví dụ 2.000.000) | `≥ deposit_min` | |
| `deposit_hold_hours` | 72 | 1–720 | R4 |
| `seller_hold_hours` | 72 | 0–720 | |
| `seller_hold_hours_high_risk` | 168 | `≥ seller_hold_hours` | |
| `complaint_window_minutes` | 15 | 5–120 | |
| `min_duration_minutes` | 60 | `> 0` | |
| `max_duration_minutes` | 1440 | `≥ min` | |
| `grace_minutes` | 10 | 0–30 | Cho `GRACE_FOR_CURRENT_MATCH` và `STAY_ONLINE_UNTIL_RECLAIM` (khóa lịch sau giờ kết thúc) |
| `platform_fee_bps` | 1000 (10%) | 0–5000 | Hoa hồng trừ từ tiền thuê |
| `renter_free_cancel_minutes` | 120 | `≥ 0` | |
| `late_cancel_refund_bps` | 5000 (50%) | 0–10000 | |
| `owner_free_cancel_hours` | 12 | `≥ 0` | |
| `min_age` | 18 | `≥ 18` | Không cho đặt thấp hơn 18 |

Thay đổi tham số game chỉ áp dụng cho đơn tạo sau đó (đơn cũ dùng `policy_snapshot`).

### 14.2. Tham số cấp ứng dụng (`application.yml`, prefix `rentgg`)

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `rentgg.order.payment-hold-minutes` | 15 | Thời gian giữ chỗ chờ thanh toán |
| `rentgg.order.owner-approval-minutes` | 30 | Thời hạn owner duyệt |
| `rentgg.order.ending-reminder-minutes` | 10 | Nhắc trước khi hết giờ |
| `rentgg.rotation.sla-minutes` | 60 | `rotation_sla_minutes` |
| `rentgg.credentials.rate-limit-per-hour` | 10 | Lượt xem credentials / giờ / order |
| `rentgg.credentials.require-reauth` | false | Bật yêu cầu nhập lại mật khẩu RentGG khi xem |
| `rentgg.security.access-token-ttl` | `PT15M` | |
| `rentgg.security.refresh-token-ttl` | `P14D` | |
| `rentgg.approval.ttl` | `PT72H` | Hạn duyệt approval |
| `rentgg.dispute.resolution-sla` | `PT72H` (`ACCOUNT_TAKEOVER`: `PT4H`) | `deadline_at` |
| `rentgg.jobs.cron` | `0 * * * * *` | Chu kỳ job |
| `rentgg.jobs.batch-size` | 100 | |
| `rentgg.outbox.max-attempts` | 10 | |
| `rentgg.payment.provider` | `FAKE` | Chọn bean `PaymentGateway` |

### 14.3. Biến môi trường

| Biến | Bắt buộc | Ví dụ / định dạng | Ghi chú |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Có | `dev` / `staging` / `prod` | `dev` bật fake gateway + simulate endpoint |
| `SPRING_DATASOURCE_URL` | Có | `jdbc:postgresql://db:5432/rentgg` | |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Có | `rentgg_app` / secret | User ứng dụng, quyền tối thiểu |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | Prod | `rentgg_migrator` | User migration riêng |
| `RENTGG_JWT_SECRET` | Có | Base64, ≥ 32 byte | Khởi động thất bại nếu thiếu/ngắn |
| `RENTGG_CREDENTIAL_KEYS` | Có | `k2026a:BASE64_32B,k2026b:BASE64_32B` | Danh sách khóa AES-256 |
| `RENTGG_CREDENTIAL_ACTIVE_KEY_ID` | Có | `k2026b` | Khóa dùng cho bản ghi mới |
| `RENTGG_PAYMENT_WEBHOOK_SECRET` | Có | Chuỗi ngẫu nhiên ≥ 32 byte | Có thể theo provider: `RENTGG_PAYMENT_WEBHOOK_SECRET_<PROVIDER>` |
| `RENTGG_PAYMENT_PROVIDER` | Không | `FAKE` | |
| `RENTGG_CORS_ALLOWED_ORIGINS` | Prod | `https://rentgg.vn` | |
| `TEST_DB_URL` (+ `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`) | Chỉ test | `jdbc:postgresql://localhost:5432/rentgg_test` | Fallback khi không có Docker cho Testcontainers |

Ở `staging`/`prod`, ứng dụng từ chối khởi động nếu dùng secret mặc định của `dev` hoặc profile `dev` cùng lúc với `prod`.

---

## 15. Nhất quán dữ liệu, transaction, concurrency

### 15.1. Ranh giới transaction

- Mỗi use case ghi là **một** `@Transactional` ở application service: chuyển trạng thái + ledger + audit + outbox cùng commit hoặc cùng rollback.
- Isolation mặc định `READ COMMITTED`; tính đúng đắn dựa vào constraint DB, row lock và optimistic locking chứ không dựa vào `SERIALIZABLE`.
- Gọi PSP (`createCheckout`) **ngoài** transaction dài: tạo intent → gọi PSP → cập nhật `provider_ref` trong transaction ngắn; gọi `refund` qua outbox consumer (sau commit).
- Job xử lý từng bản ghi trong transaction riêng (`REQUIRES_NEW` hoặc `TransactionTemplate`).

### 15.2. Chống đặt trùng — exclusion constraint

- `EXCLUDE USING gist (game_account_id WITH =, rental_period WITH &&) WHERE status IN (…4 trạng thái hoạt động…)`.
- `rental_period` là cột GENERATED từ `(start_at, block_end_at)` theo `[)` nên hai đơn liền kề (đơn A kết thúc 15:00, đơn B bắt đầu 15:00) không xung đột. Với GRACE và STAY_ONLINE, `block_end_at` đã gồm ân hạn nên khung ân hạn cũng bị khóa; search (`NOT EXISTS`) cũng cộng ân hạn của listing vào `to` của slot yêu cầu.
- Không có kiểm tra "SELECT rồi INSERT" ở ứng dụng làm nguồn sự thật (chỉ dùng để trả thông báo thân thiện sớm); constraint là trọng tài cuối cùng, không có race condition.
- Đơn chuyển sang trạng thái cuối (`EXPIRED`, `CANCELLED`, `REJECTED`, `ENDED`) tự động nhả khung giờ.

### 15.3. Row lock khi rút tiền

```sql
-- trong cùng transaction
SELECT id FROM ledger_accounts
 WHERE code = 'SELLER_AVAILABLE' AND owner_user_id = :ownerId
 FOR UPDATE;                                   -- tuần tự hóa mọi thao tác trừ available của seller

SELECT -COALESCE(SUM(amount), 0) FROM ledger_postings WHERE account_id = :accId;  -- available
-- nếu amount <= available: INSERT withdrawals + ledger WITHDRAWAL_REQUESTED; COMMIT
```

- Các thao tác **cộng** vào available (release hold, khấu trừ cọc bồi thường) không cần lock (chỉ làm số dư tăng).
- Các thao tác **trừ** available khác (`LEDGER_ADJUSTMENT` âm cho seller) cũng phải lấy cùng lock.
- Idempotency-Key unique `(owner_id, idempotency_key)` chống gửi lại cùng một yêu cầu.

### 15.4. Optimistic locking

- `@Version` trên `Listing`, `Order`, `Deposit`, `GameAccount`, `Withdrawal`, `ApprovalRequest`.
- Ví dụ: owner bấm `approve` đúng lúc job `expireOwnerApprovals` chuyển `REJECTED` → một bên thắng, bên kia nhận `OptimisticLockException` → API `409 CONCURRENT_MODIFICATION` (job thì bỏ qua và chạy lại phút sau, thấy trạng thái đã đổi).
- Khi một thao tác chạm nhiều aggregate (bắt đầu đơn: order + game account), khóa theo thứ tự cố định **order → deposit → game_account → user** để tránh deadlock; `startDueOrders` dùng `PESSIMISTIC_WRITE` trên game account vì nhiều đơn có thể tranh nhau (đơn trước kết thúc, đơn sau bắt đầu).

### 15.5. Job idempotent và webhook idempotent

- Điều kiện chọn bản ghi luôn gồm trạng thái nguồn; transaction kiểm tra lại sau khi khóa; chuyển trạng thái chỉ xảy ra một lần.
- Ledger transaction có `idempotency_key` xác định theo nghiệp vụ → ghi lại lần hai vi phạm unique, rollback an toàn.
- Webhook: `SELECT … FOR UPDATE` trên intent theo `(provider, provider_ref)`; intent đã `SUCCEEDED` → trả 200 không làm gì.
- Outbox consumer idempotent: `RefundRequested` kiểm tra `refunds.status = PENDING` trước khi gọi PSP và truyền `refundId` làm idempotency key sang PSP.

### 15.6. Bất biến cần giữ

| Bất biến | Cơ chế |
|---|---|
| Không có 2 đơn hoạt động chồng lấn trên một GameAccount | Exclusion constraint |
| Mỗi order có đúng 1 deposit | `deposits.order_id UNIQUE NOT NULL` + tạo cùng transaction |
| Mỗi GameAccount ≤ 1 listing `ACTIVE` | Partial unique index |
| Mỗi GameAccount ≤ 1 credential `ACTIVE` | Partial unique index |
| Tổng posting mỗi transaction = 0 | Service + constraint trigger deferred |
| Ledger/audit không bị sửa | Trigger append-only + quyền DB |
| Rút ≤ available | Row lock + kiểm tra số dư |
| `deducted + refunded ≤ amount` cọc | `CHECK` |
| Người duyệt ≠ người yêu cầu | Service + `CHECK` |

---

## 16. Observability

### 16.1. Log

- Logback JSON; mỗi dòng có `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId`, `userId`, `orderId` (MDC), `requestPath`, `status`, `durationMs`.
- **Không bao giờ log**: mật khẩu RentGG, credentials game, JWT/refresh token, `X-Signature`, số tài khoản ngân hàng đầy đủ. Bộ lọc log (masking) cho các khóa `password`, `token`, `secret`, `authorization` là lưới an toàn thứ hai.
- Mức log: `INFO` cho chuyển trạng thái nghiệp vụ, `WARN` cho từ chối nghiệp vụ bất thường (webhook sai chữ ký, amount lệch), `ERROR` cho lỗi hệ thống.

### 16.2. Metrics nghiệp vụ (Micrometer → `/actuator/prometheus`)

| Metric | Loại | Nhãn | Ý nghĩa |
|---|---|---|---|
| `rentgg_orders_created_total` | counter | `game` | Đơn tạo |
| `rentgg_orders_transition_total` | counter | `from`, `to`, `reason` | Chuyển trạng thái đơn (gồm hủy theo lý do) |
| `rentgg_slot_conflicts_total` | counter | — | Số lần `SLOT_UNAVAILABLE` |
| `rentgg_payment_webhooks_total` | counter | `provider`, `result` (`ok`, `duplicate`, `invalid_signature`, `amount_mismatch`, `late`) | Webhook |
| `rentgg_credential_views_total` | counter | `first_view` | Lượt xem credentials |
| `rentgg_complaint_window_disputes_total` | counter | `type` | Dispute renter |
| `rentgg_disputes_open` | gauge | `type` | Dispute đang mở |
| `rentgg_dispute_resolution_seconds` | histogram | `type` | Thời gian phân xử |
| `rentgg_accounts_by_security_state` | gauge | `state` | Số account theo trạng thái |
| `rentgg_rotation_duration_seconds` | histogram | — | Từ `ROTATION_PENDING` đến `AVAILABLE` |
| `rentgg_rotation_overdue_total` | counter | — | Quá SLA rotation |
| `rentgg_owner_fault_not_ready_total` | counter | — | Đơn hủy do account chưa sẵn sàng |
| `rentgg_deposits_by_status` / `rentgg_deposits_amount_by_status` | gauge | `status` | Số lượng/tổng tiền cọc theo trạng thái |
| `rentgg_refunds_total` | counter | `kind`, `result` | Hoàn tiền |
| `rentgg_withdrawals_requested_amount` | counter | — | Tiền yêu cầu rút |
| `rentgg_withdrawal_payout_latency_seconds` | histogram | — | Từ `REQUESTED` đến `PAID` |
| `rentgg_approvals_pending` | gauge | `action_type` | Approval chờ duyệt |
| `rentgg_job_runs_total`, `rentgg_job_duration_seconds`, `rentgg_job_failures_total` | counter/histogram | `job` | Sức khỏe job |
| `rentgg_outbox_pending`, `rentgg_outbox_oldest_age_seconds` | gauge | — | Tồn đọng outbox |
| `rentgg_ledger_imbalance` | gauge | — | Tổng toàn bộ posting (phải = 0) |

Cộng các metric chuẩn: HTTP (`http_server_requests`), JVM, HikariCP.

### 16.3. Đối soát

- Truy vấn đối soát (MVP chạy hằng ngày bằng tay hoặc qua endpoint actuator nội bộ; tự động hóa thành job ở Phase 1): tổng posting = 0; số dư `DEPOSIT_HELD` theo order khớp `deposits`; `RENT_ESCROW` = 0 với đơn ở trạng thái cuối; `REFUND_PAYABLE` khớp tổng `refunds` `PENDING/FAILED`; `PSP_CLEARING` khớp báo cáo PSP (khi có PSP thật).

### 16.4. Health và alert

- `/actuator/health` (liveness/readiness gồm DB), `/actuator/info`, `/actuator/prometheus`; các endpoint actuator khác không public.

| Alert | Điều kiện gợi ý | Mức |
|---|---|---|
| Ledger mất cân bằng | `rentgg_ledger_imbalance != 0` | Nghiêm trọng |
| Webhook sai chữ ký tăng | > 5 trong 10 phút | Cao |
| Amount mismatch webhook | ≥ 1 | Cao |
| Job không chạy | `rentgg_job_runs_total` không tăng trong 5 phút với bất kỳ job nào | Cao |
| Outbox tồn đọng | `rentgg_outbox_oldest_age_seconds > 600` | Cao |
| Refund thất bại | `rentgg_refunds_total{result="failed"}` tăng | Cao |
| `ACCOUNT_TAKEOVER` mở | Mỗi sự kiện | Cao (Support trực) |
| Dispute quá hạn phân xử | `now > deadline_at` còn `OPEN` | Trung bình |
| Rotation quá hạn nhiều | > 10% account `ROTATION_OVERDUE` | Trung bình |
| Approval chờ lâu | Pending > 24h | Trung bình |
| Rút tiền chờ lâu | `REQUESTED` > 3 ngày làm việc | Trung bình |
| Tỷ lệ 5xx | > 1% trong 5 phút | Cao |

---

## 17. Triển khai

### 17.1. Docker Compose (MVP)

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: rentgg
      POSTGRES_USER: rentgg
      POSTGRES_PASSWORD: ${DB_PASSWORD:-rentgg_dev}
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U rentgg -d rentgg"]
      interval: 5s
      retries: 10
  app:
    build: .
    depends_on:
      db: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/rentgg
      SPRING_DATASOURCE_USERNAME: rentgg
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD:-rentgg_dev}
      RENTGG_JWT_SECRET: ${RENTGG_JWT_SECRET}
      RENTGG_CREDENTIAL_KEYS: ${RENTGG_CREDENTIAL_KEYS}
      RENTGG_CREDENTIAL_ACTIVE_KEY_ID: ${RENTGG_CREDENTIAL_ACTIVE_KEY_ID}
      RENTGG_PAYMENT_WEBHOOK_SECRET: ${RENTGG_PAYMENT_WEBHOOK_SECRET}
      TZ: UTC
    ports: ["8080:8080"]
volumes:
  pgdata: {}
```

- Image app: build nhiều tầng (Maven build → `eclipse-temurin:21-jre`), chạy user không phải root, `-XX:MaxRAMPercentage=75`.
- Reverse proxy (Nginx/Caddy hoặc load balancer của cloud) kết thúc TLS ở staging/prod.

### 17.2. Môi trường

| Hạng mục | dev | staging | prod |
|---|---|---|---|
| Profile | `dev` | `staging` | `prod` |
| PaymentGateway | `FakePaymentGateway` + `/dev/payments/*` | Sandbox PSP (Phase 1) hoặc fake | PSP thật (Phase 1) |
| Swagger UI | Bật | Bật (sau xác thực) | Tắt hoặc chỉ nội bộ |
| Secrets | `.env` cục bộ, giá trị mẫu | Secret store của nền tảng triển khai | Secret store, không có giá trị mặc định |
| Dữ liệu seed | Game mẫu `ALLOWED`, user mẫu mỗi vai trò | Seed tối thiểu | Không seed (trừ admin đầu tiên qua script) |
| Số instance | 1 | 1 | 1–2 (ShedLock bảo đảm job đơn nhất) |
| Log | Text/JSON | JSON | JSON → hệ thống log tập trung |
| Postgres | Container | Managed hoặc VM có backup | Managed Postgres 16, có PITR |

### 17.3. Migration Flyway

- File `src/main/resources/db/migration/V{n}__{mô_tả}.sql`, ví dụ: `V1__extensions_and_identity.sql`, `V2__catalog_gameaccount_listing.sql`, `V3__orders_deposits_payments.sql`, `V4__ledger.sql`, `V5__dispute_withdrawal_approval.sql`, `V6__audit_notification_outbox_shedlock.sql`, `V7__append_only_triggers.sql`.
- Chạy tự động khi app khởi động (dev/staging); prod: chạy bằng user `rentgg_migrator` trong bước triển khai riêng trước khi rollout app.
- Quy tắc: không sửa migration đã áp dụng; thay đổi phá vỡ theo kiểu expand → migrate → contract qua nhiều phiên bản; migration liên quan ledger/audit phải được review bởi 2 người.

### 17.4. Backup và khôi phục

- Prod: bật PITR (WAL archiving) + full backup hằng ngày, giữ 30 ngày, mã hóa at-rest; bản sao ở vùng khác.
- Khóa mã hóa credentials **không** nằm trong backup DB; lưu riêng trong secret store và có quy trình escrow (2 người).
- Diễn tập khôi phục hằng tháng trên staging; RPO mục tiêu ≤ 5 phút, RTO ≤ 2 giờ.
- Sau khôi phục: chạy truy vấn đối soát ledger (§16.3) trước khi mở lại giao dịch.

### 17.5. Quy trình phát hành

1. CI: build, unit + integration test (Testcontainers), test kiến trúc, quét phụ thuộc.
2. Build image gắn tag theo commit.
3. Staging: migrate → deploy → smoke test (đặt đơn → thanh toán fake → bắt đầu/kết thúc bằng clock giả lập ở môi trường test).
4. Prod: migrate → deploy (rolling nếu 2 instance; graceful shutdown chờ request và job đang chạy).

---

## 18. Chiến lược kiểm thử

### 18.1. Các tầng

| Tầng | Công cụ | Phạm vi |
|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito | Tính tiền (rent/fee/net, làm tròn), chính sách hủy, máy trạng thái (chuyển hợp lệ/không hợp lệ), `CredentialCipher` (round-trip, IV khác nhau, sai AAD/tag → lỗi), HMAC verifier, validator cọc |
| Integration | Spring Boot Test + Testcontainers PostgreSQL 16 (fallback `TEST_DB_URL`) | Repository + constraint + trigger thật; service end-to-end trong transaction; job với `MutableClock` |
| API | MockMvc / `WebTestClient` | Mã HTTP, định dạng lỗi, phân quyền theo vai trò và theo tài nguyên, Idempotency-Key |
| Kiến trúc | ArchUnit (hoặc tương đương) | Quy tắc phụ thuộc module §4.4, không truy cập repository chéo module |
| Bảo mật | Test tự động + review | Không có endpoint staff xem mật khẩu; log không chứa mật khẩu (capture appender); webhook sai chữ ký |

### 18.2. Kịch bản bắt buộc (phải có test integration tương ứng)

| # | Kịch bản | Kỳ vọng |
|---|---|---|
| T1 | **Đặt trùng đồng thời:** 10 thread cùng `POST /orders` cùng account, khung giờ chồng lấn (dùng `CountDownLatch`) | Đúng 1 đơn `PENDING_PAYMENT`; 9 request `409 SLOT_UNAVAILABLE`; khung liền kề `[)` không xung đột |
| T2 | Đơn hết hạn thanh toán nhả khung giờ | Sau `expirePendingPayments`, đơn khác đặt được cùng khung |
| T3 | **Cọc thứ hai khi cọc cũ đang giữ:** đơn A `ENDED`, cọc A `POST_RENTAL_HOLD`; cùng renter tạo đơn B | Tạo deposit mới cho B; `amountToPay = rentB + depositB`; `DEPOSIT_HELD` theo order: A = dA, B = dB; hoàn A không ảnh hưởng B; không tồn tại API/tham số dùng lại cọc |
| T4 | **Ledger cân bằng:** chạy toàn bộ vòng đời (ví dụ 1, 2, 3 ở §7) | Mọi transaction tổng = 0; số dư từng tài khoản đúng như bảng §7; insert posting lệch → COMMIT thất bại (constraint trigger); UPDATE/DELETE posting → lỗi |
| T5 | **Rút tiền không vượt available:** available 81.000; 5 request song song 50.000 (khác key) | Đúng 1 thành công; còn lại `422 INSUFFICIENT_AVAILABLE_BALANCE`; cùng key gửi lại → trả bản ghi cũ, không ghi sổ thêm |
| T6 | **Dual approval không tự duyệt** | Người yêu cầu gọi approve → `403 SELF_APPROVAL_FORBIDDEN`; UPDATE trực tiếp DB `approved_by = requested_by` → vi phạm CHECK; approval hết hạn → `409` |
| T7 | **Account chưa rotation không nhận đơn** | GameAccount `ROTATION_PENDING`/`ROTATION_OVERDUE`/`AWAITING_RECLAIM`/`SUSPENDED_SECURITY_REVIEW` → `POST /orders` `409 ACCOUNT_NOT_AVAILABLE`; đơn `CONFIRMED` đã đặt trước, đến `start_at` khi account chưa `AVAILABLE` → `CANCELLED OWNER_FAULT_NOT_READY`, hoàn 100%, có `abuse_signals` |
| T8 | Webhook trùng và sai chữ ký | Gửi 2 lần cùng `provider_ref` → chỉ 1 `PAYMENT_CAPTURED`; chữ ký sai → `401`, không đổi trạng thái |
| T9 | AUTO_ACCEPT vs duyệt thủ công | `auto_accept=true` → `CONFIRMED`; `false` → `AWAITING_OWNER_APPROVAL`; quá 30' → `REJECTED` + hoàn 100%; `auto_accept` với game `OWNER_ASSISTED_OTP` → `422` |
| T10 | Cửa sổ xem credentials | Trước `start_at`, sau `block_end_at`, người khác, staff → không xem được; lần 11 trong giờ → `429`; mỗi lần xem có audit |
| T11 | Kết thúc theo 3 end mode | FORCE/GRACE → `ROTATION_PENDING` (GRACE kết thúc ở `scheduled_end_at + grace`); STAY_ONLINE → `AWAITING_RECLAIM` → reclaim → `ROTATION_PENDING`; rotation → credential version mới, cũ `RETIRED` |
| T12 | Hủy đơn | Renter đúng hạn 100%, muộn 50% + doanh thu seller tỷ lệ; owner 100% + `OWNER_LATE_CANCEL` khi sát giờ; cọc luôn hoàn 100% ngay; hủy khi `IN_RENTAL` → `409` |
| T13 | Giữ cọc và khiếu nại | `OWNER_DAMAGE` trước `hold_until` → `DISPUTED`, job không hoàn; sau `hold_until` → `422`; khấu trừ 120.000/200.000 → `SETTLED`, refund 80.000; khấu trừ > cọc → `422` |
| T14 | `ACCOUNT_TAKEOVER` trong lúc thuê | Đơn `ENDED` ngay, cọc `DISPUTED`, account `SUSPENDED_SECURITY_REVIEW`, renter `RESTRICTED` và không tạo được đơn mới; khôi phục account chỉ qua dual approval |
| T15 | Hold doanh thu | Release đúng `seller_hold_until` (168h với HIGH risk); dispute renter mở → chuyển `SELLER_DISPUTE_HOLD`, job không release |
| T16 | Fail-closed theo game | Game không `ALLOWED` → không search thấy, không tạo đơn, đơn `CONFIRMED` bị hủy khi đến giờ; đổi về `ALLOWED` từ `SUSPENDED` cần approval |
| T17 | Recovery attestation | Thiếu một cam kết → `422`; chưa `verify-recovery` → listing không submit được |
| T18 | Job chạy lại / chạy song song | Gọi mỗi job 2 lần liên tiếp và từ 2 thread → không ghi sổ trùng, không chuyển trạng thái hai lần |

### 18.3. Dữ liệu và thời gian

- Test dùng `MutableClock` (bean `Clock` thay thế) để tua tới `start_at`, `block_end_at`, `hold_until`.
- Mỗi test integration dọn dữ liệu bằng schema riêng hoặc `TRUNCATE` các bảng không append-only; với bảng append-only, test chạy trên database tạo mới cho mỗi lớp test (Testcontainers) hoặc tắt trigger trong fixture dọn dẹp bằng user migrator.

---

## 19. Lộ trình, rủi ro, quyết định còn mở

### 19.1. Lộ trình

| Phase | Nội dung | Trạng thái |
|---|---|---|
| **Phase 0 — MVP** | Toàn bộ tài liệu này: identity + KYC thủ công, catalog + ToS fail-closed, GameAccount + recovery attestation/verify (UC-44), listing + kiểm duyệt + search SQL, đơn + giữ chỗ bằng exclusion constraint, `AUTO_ACCEPT`/duyệt tay, cọc bắt buộc + giữ cọc sau thuê + hoàn tự động, bàn giao credentials trực tiếp, 3 end mode, **rotation thủ công**, dispute 4 loại gồm `ACCOUNT_TAKEOVER` (UC-43), ledger kép, hold doanh thu, rút tiền + **chi trả thủ công**, dual approval, audit, notification in-app, fake PSP | Backend đang xây theo hợp đồng |
| **Phase 1** | Tích hợp PSP thật (VNPay/PayOS) qua `PaymentGateway`; MFA cho staff; reputation/abuse score đầy đủ (UC-14); merchant tier (UC-16/UC-31); coupon (UC-32); gia hạn đơn (UC-17); chat; upload bằng chứng lên S3; FE web; job đối soát tự động; OpenTelemetry | Kế hoạch |
| **Phase 2** | Quỹ bồi hoàn listing (UC-26); gói bảo vệ account (UC-29, cần đối tác hợp lệ); Kafka; OpenSearch; Vault/KMS (envelope encryption); Redis (rate limit/cache); đa kênh (UC-19/UC-34); app mobile; Kubernetes khi tách service | Định hướng |

### 19.2. Rủi ro và giảm thiểu

| Rủi ro | Khả năng | Tác động | Giảm thiểu |
|---|---|---|---|
| Renter chiếm account, thiệt hại vượt cọc | Trung bình | Cao | R2 bắt buộc; cọc theo giá trị account (min/max theo game); `ACCOUNT_TAKEOVER` khẩn; `RESTRICTED`; tín hiệu; điều khoản pháp lý ngoài nền tảng |
| Chủ account không rotation, renter cũ vẫn vào được | Trung bình | Trung bình | Rotation bắt buộc trước đơn kế; `ROTATION_OVERDUE`; hủy đơn kế + tín hiệu; chỉ duyệt game `password_change_kicks_sessions` phù hợp |
| Game cấm cho thuê / đổi ToS | Trung bình | Cao | `tos_status` fail-closed; `PROHIBITED/SUSPENDED` chặn ngay search, tạo đơn, bắt đầu đơn; về `ALLOWED` cần dual approval |
| Sai lệch tiền | Thấp | Cao | Ledger kép bất biến, constraint cân bằng, idempotency, đối soát, dual approval cho điều chỉnh |
| Đặt trùng | Rất thấp | Cao | Exclusion constraint |
| Lộ credentials | Thấp | Cao | AES-256-GCM, khóa ngoài DB, không log, không endpoint staff, rotation sau mỗi đơn |
| Pháp lý về giữ hộ tiền (cọc, doanh thu) | Trung bình | Cao | Chi trả thủ công; chọn PSP có thu hộ/chi hộ hợp lệ (quyết định còn mở); không có ví/số dư renter |
| Cọc quá cao làm giảm chuyển đổi, quá thấp không răn đe | Cao | Trung bình | Min/max theo game; chủ tự đặt; đo trong phỏng vấn (checklist C) |
| Khối lượng dispute vượt năng lực Support | Trung bình | Trung bình | Complaint window ngắn; SLA + ưu tiên `ACCOUNT_TAKEOVER`; tín hiệu lạm dụng; Phase 1 tự động hóa một phần |
| Rate limit in-memory sai lệch khi nhiều instance | Thấp | Thấp | Chấp nhận ở MVP; Redis ở Phase 2 |
| Một Postgres là điểm lỗi đơn | Thấp | Cao | Managed Postgres + PITR; diễn tập khôi phục |
| Chủ bị khóa account bởi nhà phát hành do cho thuê | Trung bình | Cao | Chỉ game `ALLOWED`; cảnh báo rủi ro cho chủ; gói bảo vệ chỉ Phase 2 với đối tác hợp lệ |

### 19.3. Quyết định còn mở

Các mục dưới đây cần dữ liệu từ `RentGG_Validation_Checklist.md` trước khi ra mắt; kết quả được nhập vào cấu hình `games` (§5.2, §14.1) hoặc chọn triển khai `PaymentGateway`, **không** cần đổi kiến trúc.

| # | Quyết định | Phụ thuộc | Ảnh hưởng tới hệ thống | Tham chiếu checklist |
|---|---|---|---|---|
| D1 | **PSP** (VNPay / MoMo / PayOS / ZaloPay…): thu hộ, hoàn tiền một phần, giữ tiền, chi hộ, phí, webhook | Trả lời của PSP | Triển khai `PaymentGateway`, định dạng webhook/chữ ký, cách hoàn cọc (refund vs chi hộ), thời gian `REFUND_COMPLETED` | Phần B |
| D2 | **Mức cọc thực tế** (`deposit_min`/`deposit_max`) theo game | Phỏng vấn người dùng, giá trị account | Cấu hình `games` | Phần A, C |
| D3 | **Game ra mắt** (1–3 game) và tham số: ToS cho phép, `handover_mode`, `password_change_kicks_sessions`, thời lượng, ân hạn | Thử nghiệm với account của đội | `tos_status = ALLOWED` (qua dual approval nếu cần), các tham số §14.1 | Phần A |
| D4 | `deposit_hold_hours` và `seller_hold_hours` thực tế (72h có phù hợp với thời gian phát hiện thiệt hại của game?) | Thử nghiệm + phỏng vấn | Cấu hình `games` | Phần A, C |
| D5 | Có bật re-auth khi xem credentials ở MVP | Đánh giá UX | `rentgg.credentials.require-reauth` | — |
| D6 | KYC renter (hiện chỉ khai báo tuổi) | Pháp lý, tỷ lệ gian lận | Thêm điều kiện ở tạo đơn | — |

---

*Hết tài liệu — RentGG System Design Document v1.2*
