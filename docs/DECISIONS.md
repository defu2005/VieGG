# Quyết định kỹ thuật

Ngày kiểm tra **2026-11-21**. Mỗi mục ghi quyết định đang có trong code, bằng chứng, và trạng thái. Đây không phải nhật ký thay đổi. Quyết định mới: thêm một mục, không sửa mục cũ trừ khi đã đổi thật.

Trạng thái: **ACTIVE** (đang đúng trong code), **OPEN** (chưa quyết hoặc chưa có bằng chứng), **SUPERSEDED** (đã thay bằng quyết định khác).

## Kiến trúc

### D-01: Modular monolith, không microservice (ACTIVE)

- Một process backend, một PostgreSQL. Module chia theo `api` / `application` / `domain` / `infrastructure`.
- Bằng chứng: `backend/src/main/java/com/rentgg/`; `ArchitectureTest`.
- Lý do đã ghi: `docs/RentGG_System_Design_v1.2.md` §3.1.

### D-02: Không dùng Redis, Kafka, OpenSearch, Vault, Kubernetes (ACTIVE)

- Không có dependency tương ứng trong `backend/pom.xml`.
- Thay thế: sự kiện qua bảng `outbox_events` và `ApplicationEvent` (D-03); khóa job bằng ShedLock trên PostgreSQL; tìm kiếm bằng truy vấn SQL; secret qua biến môi trường.
- Lý do đã ghi: `docs/RentGG_System_Design_v1.2.md` §3.1.

### D-03: Outbox phát bằng `ApplicationEvent` trong process (ACTIVE)

- `publishOutbox` chạy mỗi 10 giây (`jobs/JobScheduler.java`), số lần thử tối đa `rentgg.outbox.max-attempts`.
- Hệ quả: không có consumer ngoài process. Nếu tách service sau này, cần thay cơ chế này. Trạng thái tách service: PLANNED (không có trong code).

### D-04: Port ngược cho phụ thuộc từ tầng thấp lên tầng cao (ACTIVE)

- Ví dụ: `payment/application/PaymentCapturedHandler` do `order` cài đặt; `notification/application/StaffDirectory`; `order/application/OrderLifecycleListener`.
- Bằng chứng: `ArchitectureTest` (`lowerLayersDoNotDependOnHigherLayers`, `noCrossModuleRepositoryAccess`).

### D-05: Đồng hồ nghiệp vụ là bean `Clock` (ACTIVE)

- Không gọi `Instant.now()` trực tiếp. Test dùng `MutableClock`; dev dùng `DevClock` để tua thời gian.
- Bằng chứng: `config/CoreConfig.java`, `jobs/devtime/`.

### D-06: Thời gian tua chỉ ở profile dev (ACTIVE)

- `DevClockConfig`, `DevTimeController`, `DevTimeService` đều `@Profile("dev")`.
- Rủi ro đã biết: nhiều backend dev dùng chung database thì tua ở một nơi ảnh hưởng dữ liệu của nơi khác (ghi trong `docs/DEVELOPMENT.md`).

## Dữ liệu

### D-07: Sổ kế toán kép, bất biến bằng trigger (ACTIVE)

- `ledger_transactions`, `ledger_postings`, `audit_logs` chặn UPDATE, DELETE, TRUNCATE (`V7__append_only_triggers.sql`).
- Tổng posting của mỗi transaction phải bằng nhau (constraint trigger trì hoãn đến commit).
- Sửa sai bằng bút toán đảo, không sửa dòng cũ.

### D-08: Trigger chặn đơn chồng lịch account game (ACTIVE)

- `V26__account_busy_blocks.sql`. Chặn ở tầng database, không chỉ ở code.

### D-09: Flyway, migration bất biến sau khi áp dụng (ACTIVE, có lệch quy tắc)

- 37 migration V1–V37 (xem [DATABASE.md](DATABASE.md#migration)).
- **Lệch**: `CLAUDE.md` và `AGENTS.md` ghi "không sửa V1–V30" và "migration mới đánh số từ V31", nhưng V31–V37 đã tồn tại. Tài liệu này không đổi quy tắc đó. Quyết định cần người quản lý dự án đưa ra: giữ nguyên quy tắc cũ, hay cập nhật theo thực tế.

### D-10: Credentials account game mã hóa AES-GCM theo phiên bản khóa (ACTIVE)

- Bảng `credential_versions`; khóa đọc từ `RENTGG_CREDENTIAL_KEYS` và `RENTGG_CREDENTIAL_ACTIVE_KEY_ID`.
- Mỗi lần xem credential được ghi vào audit.

### D-11: Media mặc định lưu trong database, S3 bật ở prod (ACTIVE)

- Cấu hình chung: `rentgg.media.storage` mặc định `db` (`${RENTGG_MEDIA_STORAGE:db}`). Khối `prod` đặt mặc định `s3`.
- Bằng chứng: `backend/src/main/resources/application.yml` (khối media ở đầu file và khối `prod`). Lệnh kiểm tra: `grep -n "RENTGG_MEDIA_STORAGE" backend/src/main/resources/application.yml`.

## Thanh toán

### D-12: Trừu tượng hóa PSP qua `PaymentGateway` và registry (ACTIVE)

- Bốn adapter: MoMo, PayOS, VNPay, ZaloPay. `PaymentGateways` chỉ dùng adapter đã cấu hình.
- Bằng chứng: `payment/application/PaymentGateways.java`, `payment/infrastructure/psp/`.

### D-13: Gateway giả bị chặn trên staging và prod (ACTIVE)

- `FakePaymentGateway` bật bằng `rentgg.payment.fake-enabled`. Mặc định `false`, dev đặt `true`.
- `StartupSafetyCheck.checkPaymentProvider()` từ chối khởi động trên staging và prod nếu provider là FAKE, không thuộc danh sách PSP thật, hoặc credential là placeholder.

### D-14: Chưa xác nhận với sandbox của PSP nào (OPEN)

- Code và test (`PaymentProvidersIT`, webhook IT) đã có, nhưng không có bằng chứng giao dịch thật với sandbox.
- Ghi trong `docs/RentGG_Implementation_Status.md`: "chưa xác nhận với sandbox".
- Việc cần để đóng mục này: chạy với sandbox của từng PSP, ghi lại kết quả. Không ghi thông tin đăng nhập trong tài liệu.

### D-15: Hoàn tiền thật qua PSP (OPEN)

- Có `RefundRepository` và job `retryFailedRefunds`. Chưa có bằng chứng chạy với PSP thật.

## Thông báo

### D-16: Thông báo hiện tại chỉ là in-app (ACTIVE)

- Không có dependency email hay push trong `backend/pom.xml`.
- Trạng thái email và push: PLANNED.

## Phạm vi module

### D-17: Module `legal` không nằm trong `ArchitectureTest.RANK` (OPEN)

- `legal` có `api` và `application`, không có `domain` và `infrastructure`.
- Hệ quả: quy tắc phụ thuộc không kiểm tra module này.
- Cần quyết định: thêm vào `RANK` với hạng phù hợp, hoặc ghi lý do không thêm.

### D-18: `settings`, `helpdesk`, `legal` không có trong bảng module của System Design (OPEN)

- `docs/RentGG_System_Design_v1.2.md` §4.3 không liệt kê ba module này.
- `MODULES.md` mô tả theo code. Cần quyết định có cập nhật System Design hay không. Tài liệu này không sửa System Design.

## Tài liệu

### D-19: Không tạo `DEPLOYMENT.md`; triển khai dùng `RentGG_Production_Deploy.md` (ACTIVE)

- `docs/RentGG_Production_Deploy.md` đã là nguồn triển khai. Tạo thêm một file trùng nội dung sẽ lệch theo thời gian.
- Các tài liệu khác trỏ thẳng tới file này.

### D-20: Không sửa System Design, Use Cases, Dev Guide, Cloud Brief, Dev Seed trong đợt tài liệu này (ACTIVE)

- Các file đó do người quản lý dự án sở hữu. Tài liệu kỹ thuật mới ghi theo code và tham chiếu tới các file đó.

## Chưa quyết định

- Ai duy trì tài liệu này và khi nào cập nhật (đề xuất: cập nhật cùng PR đổi module, endpoint, schema, hoặc lệnh chạy).
- Có đưa `legal` và `settings` vào bảng module của System Design hay không (D-18).
- Quy tắc migration: giữ hay cập nhật (D-09).
