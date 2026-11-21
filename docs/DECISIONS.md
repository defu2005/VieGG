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
- Khi commit, tổng posting của mỗi transaction phải bằng 0 và có ít nhất 2 posting (constraint trigger trì hoãn đến commit).
- Dòng sổ cũ không sửa được ở tầng database. Cách sửa sai: điều chỉnh bằng `LEDGER_ADJUSTMENT` qua duyệt kép (`approval/application/ApprovalService.java`, nhánh `LEDGER_ADJUSTMENT`). Kiểm tra 2026-11-21.

### D-08: Ràng buộc đặt lịch ở tầng database (ACTIVE)

- Đơn không chồng khoảng thuê trên cùng account: exclusion constraint `ex_orders_no_overlap` (định nghĩa cuối ở `V14`).
- Lịch chặn của account không chồng nhau: exclusion constraint `ex_account_blocks_no_overlap` (`V26`).
- Đơn không giao với lịch chặn của account: trigger `trg_orders_account_blocks` (`V26`).
- Chặn ở database, không chỉ ở code.

### D-09: Flyway, migration bất biến sau khi áp dụng (ACTIVE, có lệch quy tắc)

- 37 migration V1–V37 (xem [DATABASE.md](DATABASE.md#migration)).
- **Lệch**: `CLAUDE.md` và `AGENTS.md` ghi "không sửa V1–V30" và "migration mới đánh số từ V31", nhưng V31–V37 đã tồn tại. Tài liệu này không đổi quy tắc đó. Quyết định cần người quản lý dự án đưa ra: giữ nguyên quy tắc cũ, hay cập nhật theo thực tế.

### D-10: Credentials account game mã hóa AES-GCM theo phiên bản khóa (ACTIVE)

- Mã hóa `AES/GCM/NoPadding` (`shared/crypto/CredentialCipher.java`). Khóa đọc từ `RENTGG_CREDENTIAL_KEYS`; khóa dùng để mã hóa mới là `RENTGG_CREDENTIAL_ACTIVE_KEY_ID`.
- Bảng `credential_versions` lưu các phiên bản; phiên bản đang dùng có `status = 'ACTIVE'`.
- Tạo và xoay vòng credential được ghi audit. Khi xem credential để bàn giao, ghi `CREDENTIAL_VIEWED`; từ chối và vượt giới hạn ghi `CREDENTIAL_VIEW_DENIED` và `CREDENTIAL_VIEW_RATE_LIMITED` (`order/application/HandoverService.java`). Kiểm tra 2026-11-21.

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

### D-17: Module `legal` ở hạng 1 trong `ArchitectureTest.RANK` (ACTIVE)

- `legal` có `api` và `application`, không có `domain` và `infrastructure`.
- Lý do chọn hạng 1: `legal` chỉ import `audit` và `shared`, và chỉ `jobs` (hạng 7) import nó. Kiểm tra 2026-11-21: `./mvnw -B test -Dtest=ArchitectureTest` pass.
- Hệ quả: `legal` không được import `identity` hay `order` nếu không đổi hạng. Muốn đổi thì cần sửa `RANK` và ghi mục mới ở đây.

### D-18: `settings`, `helpdesk`, `legal` không có trong bảng module của System Design (OPEN)

- `docs/RentGG_System_Design_v1.2.md` §4.3 không liệt kê ba module này.
- `MODULES.md` mô tả theo code. Cần quyết định có cập nhật System Design hay không. Tài liệu này không sửa System Design.

## Tài liệu

### D-19: Không tạo `DEPLOYMENT.md`; triển khai dùng `RentGG_Production_Deploy.md` (ACTIVE)

- `docs/RentGG_Production_Deploy.md` đã là nguồn triển khai. Tạo thêm một file trùng nội dung sẽ lệch theo thời gian.
- Các tài liệu khác trỏ thẳng tới file này.

### D-20: Không sửa System Design, Use Cases, Dev Guide, Cloud Brief, Dev Seed trong đợt tài liệu này (ACTIVE)

- Các file đó do người quản lý dự án sở hữu. Tài liệu kỹ thuật mới ghi theo code và tham chiếu tới các file đó.

## Khác biệt so với tài liệu thiết kế (ghi theo code)

Các điểm dưới đây có trong code nhưng không có (hoặc khác) trong `docs/RentGG_System_Design_v1.2.md`. Kiểm tra 2026-11-21.

### D-21: Endpoint bổ sung (ACTIVE)

- `POST /api/v1/orders/{id}/otp-note`: ghi chú OTP trên đơn (`order/api/OrderController.java`). Chưa đối chiếu với UC-08 trong `docs/RentGG_Use_Cases_China_Model_v8.md`; ý nghĩa nghiệp vụ UNKNOWN.
- `GET /api/v1/admin/ledger/reconciliation`: đối soát (`ledger/api/LedgerAdminController.java`).
- `GET /api/v1/admin/ledger/orders/{orderId}`: sổ kế toán của một đơn (cùng controller).

### D-22: Khiếu nại rủi ro của người thuê chỉ trong thời gian thuê (ACTIVE)

- Loại `RENTER_RISK` chỉ người thuê mở được, và chỉ khi đơn ở trạng thái `IN_RENTAL` (`dispute/application/DisputeService.java`, nhánh `RENTER_RISK`, lỗi `COMPLAINT_WINDOW_CLOSED`).

### D-23: Hoàn tiền lỗi được thử lại bằng job (ACTIVE, cơ chế chi tiết UNKNOWN)

- Job `retryFailedRefunds` thử lại các hoàn tiền lỗi (`jobs/Jobs.java`). Chưa kiểm được chính sách backoff trong code: UNKNOWN.

### D-24: Giao diện web: nền tối mặc định, cam chỉ cho hành động và giá (ACTIVE)

- **Quyết định:** web dùng nền tối làm mặc định (`:root`); chế độ sáng chỉ bật khi có `data-theme="light"`. Hiện chưa có nút chuyển, nên người dùng không tự chọn được và không theo hệ điều hành.
- **Cam (`--primary` / `--price`):** chỉ dùng cho nút hành động chính, liên kết và giá. Trạng thái (giữ, chờ, hoàn tất, tranh chấp, hủy) không dùng cam. Chữ trên nút cam dùng màu tối `#222222` (5.5:1).
- **Ứng dụng di động:** giữ nền sáng, chỉ đồng bộ màu cam nhấn. Web và app chưa khớp hoàn toàn về nền.
- **Kiểm tra:** tương phản WCAG (chữ chính 17.3:1, chữ mờ 6.8:1, link cam 6.7:1, đỏ cảnh báo 5.9:1); không tràn ngang ở 1280 và 390 trên 7 trang; E2E `web-evidence` 8/8.
- **Chưa làm:** nút chuyển sáng/tối; ảnh placeholder listing vẫn đơn giản; tên game trên đơn lấy từ `/games` phía client (API đơn không trả tên); tour chào mừng chưa lưu trạng thái phía server; bảng đơn và bảng ví trên 390px cuộn ngang trong thẻ thay vì rút gọn cột.
- **Liên quan:** nhánh `claude/vigilant-heisenberg-xp4z0i` (commit `c442f81` và các commit sau).

## Chưa quyết định

- Ai duy trì tài liệu này và khi nào cập nhật (đề xuất: cập nhật cùng PR đổi module, endpoint, schema, hoặc lệnh chạy).
- Có đưa `legal` và `settings` vào bảng module của System Design hay không (D-18).
- Quy tắc migration: giữ hay cập nhật (D-09).

### Danh sách tính năng đề xuất (PLANNED, chưa được duyệt)

Ghi ngày 2026-11-21. Mỗi mục đã kiểm tra bằng code hoặc tài liệu như ghi bên dưới. Chưa làm mục nào; chờ người quản lý dự án chọn.

1. **Email và push cho sự kiện có hạn chót** (hạn thanh toán, bắt đầu, kết thúc thuê, gia hạn, hạn khiếu nại, rotation quá hạn). Bằng chứng: không có dependency email hay push (D-16); outbox và 41 mẫu thông báo đã có. Làm adapter theo kiểu mock trước, như Dev Guide §4.3.
2. **Hoàn tiền về phương thức thanh toán gốc qua PSP.** Bằng chứng: `PaymentService.processRefund` luôn cộng vào ví; Dev Guide §4.1 mục hoàn tiền còn mở. Phần `[M]` làm được bằng contract test, không cần khóa sandbox.
3. **Xác minh bổ sung (step-up) khi rút tiền.** Bằng chứng: `wallet/` không có TOTP hay MFA; TOTP hiện chỉ bắt buộc cho nhân viên (Dev Guide §2.4).
4. **Đánh giá và điểm uy tín hai chiều.** Bằng chứng: không có module rating hay review; UC-14 là P1 trong use case; xem `RentGG_Frontend_Status.md` mục 4 (8b).
5. **Lưu trạng thái "đã xem tour" phía server.** Bằng chứng: `frontend/src/components/Onboarding.tsx` lưu trong localStorage theo `userId`, nên tour hiện lại trên mỗi thiết bị. Việc nhỏ.

Đã loại khỏi danh sách: điều chỉnh sổ cái (đã có `LEDGER_ADJUSTMENT`), audit xem credential (đã có `CREDENTIAL_VIEWED`), gói bảo vệ account và đồng bộ đa kênh (phụ thuộc đối tác, Phase 2).
