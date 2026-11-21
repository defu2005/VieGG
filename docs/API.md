# API

Ngày kiểm tra **2026-11-21**, theo code trong `backend/src/main/java/com/rentgg/`. Tài liệu này không liệt kê từng endpoint. Danh sách đầy đủ, kèm schema, lấy từ OpenAPI khi chạy profile `dev` (xem [Tài liệu OpenAPI](#tài-liệu-openapi)).

## Tổng quan

| Mục | Giá trị |
|---|---|
| Base path | `/api/v1` |
| Số endpoint (annotation `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`) | 149 |
| Số controller (`@RestController`) | 25 |
| Định dạng | JSON; upload ảnh và tin nhắn hỗ trợ có đính kèm qua `multipart/form-data` |
| Xác thực | `Authorization: Bearer <access token>` |
| Phân quyền | `@PreAuthorize` trên từng endpoint; test `AuthorizationCoverageTest` kiểm tra mọi controller |

Đếm endpoint: `grep -rhoE "@(Get|Post|Put|Patch|Delete)Mapping" backend/src/main/java | wc -l`.

## Controller theo module

Số trong cột "Endpoint" đếm theo annotation trong file, ngày 2026-11-21. Lệnh kiểm tra cho từng file: `grep -cE "@(Get|Post|Put|Patch|Delete)Mapping" <file>`.

| Module | Controller | Base path | Endpoint |
|---|---|---|---|
| audit | `AdminAuditController` | `/api/v1/admin/audit` | 1 |
| approval | `ApprovalController` | `/api/v1/admin` | 5 |
| identity | `AuthController` | `/api/v1/auth` | 4 |
| identity | `MeController` | `/api/v1/me` | 5 |
| identity | `TotpController` | `/api/v1/me/mfa/totp` | 2 |
| identity | `AdminUserController` | `/api/v1/admin/users` | 5 |
| identity | `AdminRbacController` | `/api/v1/admin/rbac` | 10 |
| identity | `StaffUserController` | `/api/v1/staff/users` | 2 |
| catalog | `GameController` | `/api/v1` | 6 |
| gameaccount | `GameAccountController` | `/api/v1` | 10 |
| listing | `ListingController` | `/api/v1` | 17 |
| listing | `ListingAppealController` | `/api/v1` | 4 |
| order | `OrderController` | `/api/v1/orders` | 16 |
| payment | `PaymentController` | `/api/v1/payments` | 3 (có thêm endpoint dev, xem bên dưới) |
| dispute | `DisputeController` | `/api/v1` | 9 |
| dispute | `DisputeAppealController` | `/api/v1` | 3 |
| wallet | `WalletController` | `/api/v1` | 10 |
| ledger | `LedgerAdminController` | `/api/v1/admin/ledger` | 3 |
| helpdesk | `HelpdeskController` | `/api/v1` | 16 |
| legal | `LegalNoticeController` | `/api/v1/support/legal-notices` | 3 |
| legal | `LegalNoticeTransitionController` | `/api/v1/support/legal-notices` | 1 |
| notification | `NotificationController` | `/api/v1/notifications` | 4 |
| settings | `SettingsController` | `/api/v1` | 3 |
| jobs | `DevController` | `/api/v1/dev/jobs` | 1 (chỉ profile `dev`) |
| jobs | `DevTimeController` | `/api/v1/dev/time` | 6 (chỉ profile `dev`) |

Ghi chú:

- `GlobalExceptionHandler` là `@RestControllerAdvice`, không có endpoint.
- Module `deposit` không có controller. Cọc đi qua `OrderController` và `WalletController`.
- `PaymentController` có endpoint `/api/v1/dev/payments` (một `@RequestMapping` cấp method), bật ở profile `dev` và `test`.

## Endpoint công khai (không cần token)

Nguồn: `shared/security/SecurityConfig.java`. Kiểm tra 2026-11-21.

- `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout`
- `GET /api/v1/games`, `GET /api/v1/games/*`
- `GET /api/v1/settings/public`
- `GET /api/v1/listings/search`, `GET /api/v1/listings/*`, `GET /api/v1/listings/*/images/*`
- `GET` và `POST /api/v1/payments/webhook/{provider}`, `GET /api/v1/payments/return/*` (PSP gọi vào; mỗi adapter kiểm tra chữ ký theo quy định của nhà cung cấp. `FakePaymentGateway` ký bằng HMAC-SHA256 trong header `X-Signature` với `rentgg.payment.webhook-secret`)
- `/swagger-ui/**`, `/v3/api-docs/**` (chỉ khi profile cho phép)
- `/actuator/health`, `/actuator/info`, `/actuator/prometheus`
- `/error`

Mọi endpoint `/api/v1/**` còn lại yêu cầu token hợp lệ.

## Quy ước

### Xác thực và phân quyền

- Access token là JWT, gửi qua header `Authorization: Bearer <token>`.
- Refresh token gọi `POST /api/v1/auth/refresh`. Refresh token được lưu trong bảng `refresh_tokens`.
- Nhân viên có thể yêu cầu MFA bằng TOTP (`/api/v1/me/mfa/totp`).
- Quyền là RBAC (`roles`, `permissions`, `role_permissions`). Quản trị quyền qua `/api/v1/admin/rbac`.

### Lỗi

Mọi lỗi trả về JSON dạng `ErrorResponse` (`backend/src/main/java/com/rentgg/shared/error/ErrorResponse.java`):

```json
{
  "code": "MÃ_LỖI",
  "message": "Thông điệp",
  "details": {},
  "traceId": "..."
}
```

- `code` là mã máy đọc, ví dụ `IDEMPOTENCY_KEY_REQUIRED`, `PAYMENT_PROVIDER_UNAVAILABLE`.
- `traceId` khớp với log của request.
- Danh sách mã đầy đủ nằm rải rác trong các `*Exception`. UNKNOWN: không có bảng mã lỗi tập trung.

### Idempotency

- Tạo đơn (`POST /api/v1/orders`) bắt buộc header `Idempotency-Key`. Thiếu header thì trả `IDEMPOTENCY_KEY_REQUIRED`.
- CORS cho phép các header `Authorization`, `Content-Type`, `Idempotency-Key`.

### Phân trang

- Danh sách dùng `PageResponse` (`shared/web/PageResponse.java`) và `Paging` (`shared/web/Paging.java`). Tham số và cấu trúc cụ thể xem trong controller tương ứng.

### Giới hạn

- Body JSON bị giới hạn kích thước bởi `JsonBodyLimitFilter`. Ngưỡng cụ thể nằm trong code; test `JsonBodyLimitIT` kiểm tra.
- Giới hạn tốc độ bằng Bucket4j. Ngưỡng cấu hình trong `rentgg.rate-limit` (`RentGgProperties`).

## Tài liệu OpenAPI

- Bật ở profile `dev` và `test`: `/v3/api-docs` và `/swagger-ui/index.html`.
- Tắt ở `prod` và `staging`. Các test `com/rentgg/docs/*` kiểm tra điều này (`ProdApiDocsDisabledIT`, `StagingApiDocsDisabledIT`).
- Lấy OpenAPI JSON khi backend dev chạy: `curl http://localhost:8080/v3/api-docs` (cần profile `dev`).

## Thêm hoặc đổi endpoint

- Đặt `@PreAuthorize` trên mọi endpoint mới. `AuthorizationCoverageTest` sẽ báo lỗi nếu thiếu.
- Đặt controller trong `api/` của module sở hữu nghiệp vụ. Xem [MODULES.md](MODULES.md).
- Đổi hợp đồng (tên trường, mã lỗi, đường dẫn) ảnh hưởng đến web, Android và iOS. Cập nhật client tương ứng trong cùng PR.
- Client web gọi `API_BASE = '/api/v1'` (`frontend/src/api/client.ts`). Vite proxy `/api` tới backend (xem [DEVELOPMENT.md](DEVELOPMENT.md)).
