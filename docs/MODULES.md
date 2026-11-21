# Module backend

Nguồn duy nhất về danh sách module. Ghi theo code tại `backend/src/main/java/com/rentgg/`, ngày kiểm tra **2026-11-21** (nhánh `claude/docs-system` từ `dev` @ `e439d1e`).

Mỗi module có cấu trúc `api` (controller, DTO) / `application` (service, use case) / `domain` (entity, quy tắc) / `infrastructure` (repository, adapter). Ngoại lệ được ghi trong bảng.

## Bảng module

Cột **Hạng** là thứ tự tầng trong `ArchitectureTest.RANK` (`backend/src/test/java/com/rentgg/ArchitectureTest.java`). Module tầng thấp không được phụ thuộc module tầng cao hơn.

| Module | Hạng | Trách nhiệm chính | Controller (base path) |
|---|---|---|---|
| `shared` | 0 | Lỗi thống nhất (`error`), bảo mật và JWT (`security`), mã hóa AES-GCM (`crypto`), outbox (`outbox`), lưu media (`media`), trace id và giới hạn body (`web`) | không có |
| `config` | 0 | Bean cấu hình: `Clock` (`CoreConfig`), kiểm tra an toàn khi khởi động (`StartupSafetyCheck`), `RentGgProperties` | không có |
| `audit` | 1 | Audit append-only cho thao tác nhạy cảm và mọi lần xem credentials | `/api/v1/admin/audit` |
| `notification` | 1 | Thông báo **in-app** theo sự kiện. Chưa có email hay push (xem [DECISIONS.md](DECISIONS.md)) | `/api/v1/notifications` |
| `identity` | 2 | Đăng ký, đăng nhập, refresh token, hồ sơ, TOTP cho nhân viên, RBAC, quản trị người dùng | `/api/v1/auth`, `/api/v1/me`, `/api/v1/me/mfa/totp`, `/api/v1/admin/users`, `/api/v1/admin/rbac`, `/api/v1/staff/users` |
| `catalog` | 2 | Danh mục game và tham số chính sách theo game | `/api/v1` (`GameController`: `/games`) |
| `ledger` | 2 | Sổ kế toán kép: tài khoản, transaction, posting; đối soát | `/api/v1/admin/ledger` |
| `settings` | 2 | Cấu hình ứng dụng: đọc công khai và admin sửa | `/api/v1/settings/public`, `/api/v1/admin/settings` |
| `gameaccount` | 3 | Account game, credentials đã mã hóa, rotation, reclaim, xác minh | `/api/v1` (`GameAccountController`) |
| `payment` | 3 | `PaymentIntent`, checkout, webhook PSP, refund, abstraction `PaymentGateway`, gateway giả cho dev | `/api/v1/payments`, `/api/v1/dev/payments` (chỉ profile `dev` và `test`) |
| `deposit` | 3 | Vòng đời cọc, 1–1 với đơn: hold, hoàn, khấu trừ | không có controller riêng; dùng qua `order` và `wallet` |
| `listing` | 4 | Listing: tạo, sửa, nộp duyệt, kiểm duyệt, khiếu nại kiểm duyệt, tìm kiếm | `/api/v1` (`ListingController`, `ListingAppealController`) |
| `order` | 5 | Đơn: tạo và giữ chỗ, duyệt của chủ, bắt đầu/kết thúc, hủy, gia hạn, OTP, xác nhận bàn giao | `/api/v1/orders` |
| `dispute` | 6 | Khiếu nại: mở, bằng chứng, phân xử, kháng nghị | `/api/v1` (`DisputeController`, `DisputeAppealController`) |
| `wallet` | 6 | Ví: số dư (projection từ ledger), nạp, rút tiền | `/api/v1` (`WalletController`) |
| `approval` | 6 | Dual approval (hai người duyệt, khác nhau) | `/api/v1/admin` (`ApprovalController`) |
| `helpdesk` | 6 | Ticket hỗ trợ và tin nhắn, có đính kèm | `/api/v1` (`HelpdeskController`) |
| `legal` | **không có** | Thông báo pháp lý (`legal_notices`) và chuyển trạng thái | `/api/v1/support/legal-notices` |
| `jobs` | 7 | Job định kỳ (`JobScheduler`, `Jobs`), dữ liệu mẫu dev (`DevDataSeeder`), tua thời gian dev (`devtime/`), backfill media (`media/`) | `/api/v1/dev/jobs`, `/api/v1/dev/time` (chỉ profile dev) |

Ghi chú về bảng:

- **`legal`** không có trong `ArchitectureTest.RANK`, nên quy tắc phụ thuộc không kiểm tra module này. Nó cũng không có `domain/` và `infrastructure/`. Trạng thái: UNKNOWN về việc cần thêm hạng hay không. Xem [DECISIONS.md](DECISIONS.md).
- **`settings`**, **`helpdesk`** và **`legal`** không có trong bảng module của System Design v1.2 (`docs/RentGG_System_Design_v1.2.md` §4.3). Bảng này mô tả theo code.
- **`deposit`** không có controller. Cọc được tạo và trả qua `order`, số dư qua `wallet`.
- **`jobs`** có `DevController`, `DevTimeController` và `DevDataSeeder` chỉ chạy ở profile `dev` (xem [ARCHITECTURE.md](ARCHITECTURE.md#cấu-hình-và-profile)).

## Phụ thuộc giữa module

Quy tắc (đã kiểm tra tự động):

1. Module tầng thấp không import class của module tầng cao (`ArchitectureTest.lowerLayersDoNotDependOnHigherLayers`).
2. Module không import `infrastructure` của module khác (`ArchitectureTest.noCrossModuleRepositoryAccess`). Cần dữ liệu của module khác thì gọi service hoặc đọc qua port.
3. Khi tầng thấp cần phản ứng với tầng cao, dùng **port ngược** (interface do tầng thấp khai báo, tầng cao cài đặt). Ví dụ: `PaymentCapturedHandler`, `OrderLifecycleListener`, `StaffDirectory`.

Phụ thuộc chính, theo import thực tế ở các tầng trên:

```text
jobs ──► order, dispute, listing, deposit, wallet, payment, ...   (chỉ jobs được gọi nhiều module)
order ──► listing, gameaccount, payment, deposit, ledger, notification, audit
dispute, wallet, approval, helpdesk ──► (tầng 6, dùng ledger, deposit, audit, notification)
payment ──► ledger (qua port), notification
```

Sơ đồ trên là tóm tắt, không đầy đủ. Muốn chắc, chạy `ArchitectureTest` hoặc xem import.

## Nơi bắt đầu đọc code

| Muốn sửa | Đọc trước |
|---|---|
| Đăng nhập, token, quyền | `shared/security/SecurityConfig.java`, `identity/api/AuthController.java` |
| Đơn và giữ chỗ | `order/application/` (service tạo đơn), `order/api/OrderController.java` |
| Thanh toán và webhook | `payment/application/PaymentService.java`, `payment/application/PaymentGateways.java`, `payment/infrastructure/psp/` |
| Sổ kế toán | `ledger/` (posting là bất biến, xem [DATABASE.md](DATABASE.md)) |
| Job định kỳ | `jobs/JobScheduler.java` (lịch), `jobs/Jobs.java` (logic) |
| Cấu hình | `config/RentGgProperties.java` và `backend/src/main/resources/application.yml` |

## Quy tắc khi thêm hoặc sửa module

- Đặt code mới đúng `api` / `application` / `domain` / `infrastructure`. Đừng đặt repository ngoài `infrastructure`.
- Thêm module mới: đặt hạng trong `ArchitectureTest.RANK`. Nếu không thêm, quy tắc phụ thuộc không áp dụng cho module đó.
- Đổi quyền truy cập endpoint: `AuthorizationCoverageTest` kiểm tra mọi controller có `@PreAuthorize`. Xem [API.md](API.md).
- Đổi bảng hoặc cột: xem [DATABASE.md](DATABASE.md#migration).
- Module mới hoặc thay đổi trách nhiệm: cập nhật bảng ở file này (xem quy tắc trong `CLAUDE.md`).
