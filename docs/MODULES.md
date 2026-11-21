# Module backend

Nguồn duy nhất về danh sách module. Ghi theo code tại `backend/src/main/java/com/rentgg/`, ngày kiểm tra **2026-11-21** (nhánh `claude/docs-system` từ `dev` @ `e439d1e`).

Bố cục chuẩn của module nghiệp vụ là `api` (controller, DTO) / `application` (service, use case) / `domain` (entity, quy tắc) / `infrastructure` (repository, adapter). Kiểm tra 2026-11-21 bằng `[ -d backend/src/main/java/com/rentgg/<module>/<tầng> ]` cho từng module, các ngoại lệ là:

- Thiếu tầng: `audit` (thiếu `domain`, `infrastructure`), `legal` (thiếu `domain`, `infrastructure`), `deposit` (thiếu `api`, vì không có controller riêng; xem bảng module), `ledger` (thiếu `infrastructure`), `settings` (thiếu `infrastructure`).
- Không có thư mục tầng: `config` và `jobs` (class nằm ở gốc package), `shared` (package hạ tầng dùng chung theo chức năng: `crypto`, `error`, `logging`, `media`, `outbox`, `ratelimit`, `security`, `web`).

## Bảng module

Cột **Hạng** là thứ tự tầng trong `ArchitectureTest.RANK` (`backend/src/test/java/com/rentgg/ArchitectureTest.java`). Module tầng thấp không được phụ thuộc module tầng cao hơn.

| Module | Hạng | Trách nhiệm chính | Controller (base path) |
|---|---|---|---|
| `shared` | 0 | Lỗi thống nhất (`error`), bảo mật và JWT (`security`), mã hóa AES-GCM (`crypto`), outbox (`outbox`), lưu media (`media`), trace id và giới hạn body (`web`) | không có |
| `config` | 0 | Bean cấu hình: `Clock` (`CoreConfig`), kiểm tra an toàn khi khởi động (`StartupSafetyCheck`), `RentGgProperties` | không có |
| `audit` | 1 | Audit append-only cho thao tác nhạy cảm (ví dụ tạo và xoay vòng credential) | `/api/v1/admin/audit` |
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
| `legal` | 1 | Thông báo pháp lý (`legal_notices`) và chuyển trạng thái | `/api/v1/support/legal-notices` |
| `jobs` | 7 | Job định kỳ (`JobScheduler`, `Jobs`), dữ liệu mẫu dev (`DevDataSeeder`), tua thời gian dev (`devtime/`), backfill media (`media/`) | `/api/v1/dev/jobs`, `/api/v1/dev/time` (chỉ profile dev) |

Ghi chú về bảng:

- **`legal`** có hạng 1 trong `ArchitectureTest.RANK` (kiểm tra 2026-11-21). Nó chỉ import `audit` và `shared`. Nó không có `domain/` và `infrastructure/`. Xem [DECISIONS.md](DECISIONS.md) (D-17).
- **`settings`**, **`helpdesk`** và **`legal`** không có trong bảng module của System Design v1.2 (`docs/RentGG_System_Design_v1.2.md` §4.3). Bảng này mô tả theo code.
- **`deposit`** không có controller. Cọc được tạo và trả qua `order`, số dư qua `wallet`.
- **`jobs`** có `DevController`, `DevTimeController` và `DevDataSeeder` chỉ chạy ở profile `dev` (xem [ARCHITECTURE.md](ARCHITECTURE.md#cấu-hình-và-profile)).

## Phụ thuộc giữa module

Quy tắc (đã kiểm tra tự động):

1. Module tầng thấp không import class của module tầng cao (`ArchitectureTest.lowerLayersDoNotDependOnHigherLayers`).
2. Module không import `infrastructure` của module khác (`ArchitectureTest.noCrossModuleRepositoryAccess`). Cần dữ liệu của module khác thì gọi service hoặc đọc qua port.
3. Khi tầng thấp cần phản ứng với tầng cao, dùng **port ngược** (interface do tầng thấp khai báo, tầng cao cài đặt). Ví dụ: `PaymentCapturedHandler`, `OrderLifecycleListener`, `StaffDirectory`.

Ma trận import thực tế (quét import `com.rentgg.<module>` trong mã nguồn, kiểm tra 2026-11-21). Lệnh để kiểm lại cho một module:

```bash
grep -rhoE "import com\.rentgg\.[a-z]+" backend/src/main/java/com/rentgg/<module> | sort -u
```

| Module (hạng) | Import từ các module |
|---|---|
| `shared` (0) | `config` |
| `config` (0) | — |
| `audit` (1) | `shared` |
| `notification` (1) | `shared` |
| `identity` (2) | `audit`, `config`, `notification`, `shared` |
| `catalog` (2) | `audit`, `shared` |
| `ledger` (2) | — |
| `settings` (2) | `audit`, `shared` |
| `gameaccount` (3) | `audit`, `catalog`, `config`, `identity`, `shared` |
| `payment` (3) | `audit`, `config`, `ledger`, `shared` |
| `deposit` (3) | `ledger`, `payment`, `shared` |
| `listing` (4) | `audit`, `catalog`, `gameaccount`, `identity`, `shared` |
| `order` (5) | `audit`, `catalog`, `config`, `deposit`, `gameaccount`, `identity`, `ledger`, `listing`, `payment`, `shared` |
| `dispute` (6) | `audit`, `catalog`, `config`, `deposit`, `gameaccount`, `identity`, `ledger`, `order`, `payment`, `shared` |
| `wallet` (6) | `audit`, `deposit`, `dispute`, `identity`, `ledger`, `order`, `payment`, `settings`, `shared` |
| `approval` (6) | `audit`, `catalog`, `config`, `gameaccount`, `ledger`, `shared`, `wallet` |
| `helpdesk` (6) | `audit`, `dispute`, `identity`, `order`, `shared` |
| `legal` (1) | `audit`, `shared` |
| `jobs` (7) | `approval`, `catalog`, `config`, `deposit`, `gameaccount`, `identity`, `ledger`, `listing`, `order`, `payment`, `shared`, `wallet` |

Đọc bảng: module hạng cao gọi module hạng thấp hoặc cùng hạng là hợp lệ. Module `legal` nằm ở hạng 1, nên quy tắc phụ thuộc kiểm tra cả module này.

Đây là quét import tĩnh. Không bao gồm gọi qua reflection hay cấu hình Spring. Quy tắc tự động là `ArchitectureTest`; bảng này chỉ để đọc nhanh.

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
