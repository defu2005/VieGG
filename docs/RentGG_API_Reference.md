# RentGG — Tham chiếu API

Bảng này lấy từ khai báo `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` và `@PreAuthorize` trong `backend/src/main/java/`, không từ tài liệu. Đường dẫn ghi không có tiền tố `/api/v1`. Tất cả đều nằm dưới `/api/v1`.

## Cách lấy và giới hạn

- Không xuất OpenAPI từ springdoc. Backend và PostgreSQL không chạy trong container lượt này, nên chưa thử export. Nếu cần OpenAPI chính xác, chạy backend profile `dev` rồi lấy `/v3/api-docs`.
- Phân tích bằng biểu thức chính quy trên mã, không phải AST. Số annotation ánh xạ trong mã là 176: 26 ở cấp lớp (kể cả lớp lồng `DevPaymentController`) và 150 ở cấp phương thức, khớp số dòng bảng. Trường hợp đã kiểm tay: `DevPaymentController` là lớp lồng có base riêng; `StaffUserController` dùng hằng `ANY_STAFF_LOOKUP`; `PaymentController.webhook` khai báo bằng `@RequestMapping(method=...)` và nhận cả GET lẫn POST.
- Đối chiếu với `RentGG_System_Design_v1.2.md` §11.2: 54 dòng trong §11.2; 54 có trong controller; 0 không có. Controller có 150 cặp phương thức–đường dẫn, trong đó 96 không có trong §11.2. §11.2 là danh sách một phần, không phải danh sách đầy đủ.
- `AuthorizationCoverageTest` (`backend/src/test/java/com/rentgg/AuthorizationCoverageTest.java`) kiểm tra mọi endpoint `/admin`, `/support`, `/finance`, `/staff` đều có `@PreAuthorize` (kể cả ở cấp lớp). Test chỉ kiểm tra **có** annotation, không kiểm tra biểu thức đúng hay không. Biểu thức trong bảng dưới chưa đối chiếu với ma trận quyền của vai trò.
- Các endpoint không có `@PreAuthorize`: SecurityConfig cho phép đăng nhập là đủ, và kiểm tra quyền sở hữu nằm trong service. Đã đối chiếu số dòng `@PreAuthorize` của từng controller với bảng này (khớp; hai lớp `StaffUserController` và `LedgerAdminController` có `@PreAuthorize` cấp lớp, các dòng của chúng được tính kế thừa). Chưa kiểm chứng từng endpoint bằng cách gọi thật.
- Cột "Chỉ dev" đánh dấu endpoint có `@Profile` chứa `dev`. Lưu ý: các endpoint `/dev/time/*` không có `@PreAuthorize`. Theo mã, mọi người dùng đăng nhập đều gọi được trong profile dev. Chưa kiểm chứng bằng cách chạy thật.

## Endpoint theo controller

### AdminAuditController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/audit` | `@PreAuthorize(hasAuthority('audit:read'))` |  |

### AdminRbacController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/rbac/permissions` | `@PreAuthorize(hasAuthority('role:read'))` |  |
| GET | `/admin/rbac/roles` | `@PreAuthorize(hasAuthority('role:read'))` |  |
| POST | `/admin/rbac/roles` | `@PreAuthorize(hasAuthority('role:manage'))` |  |
| DELETE | `/admin/rbac/roles/{code}` | `@PreAuthorize(hasAuthority('role:manage'))` |  |
| GET | `/admin/rbac/roles/{code}` | `@PreAuthorize(hasAuthority('role:read'))` |  |
| PUT | `/admin/rbac/roles/{code}` | `@PreAuthorize(hasAuthority('role:manage'))` |  |
| PUT | `/admin/rbac/roles/{code}/permissions` | `@PreAuthorize(hasAuthority('role:manage'))` |  |
| GET | `/admin/rbac/users/{userId}/roles` | `@PreAuthorize(hasAuthority('role:read'))` |  |
| POST | `/admin/rbac/users/{userId}/roles` | `@PreAuthorize(hasAuthority('role:assign'))` |  |
| DELETE | `/admin/rbac/users/{userId}/roles/{role}` | `@PreAuthorize(hasAuthority('role:assign'))` |  |

### AdminUserController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/users/{id}` | `@PreAuthorize(hasAuthority('user:read'))` |  |
| GET | `/admin/users/{id}/abuse-signals` | `@PreAuthorize(hasAuthority('user:read_abuse_signals'))` |  |
| POST | `/admin/users/{id}/kyc` | `@PreAuthorize(hasAuthority('user:kyc_decide'))` |  |
| POST | `/admin/users/{id}/risk-tier` | `@PreAuthorize(hasAuthority('user:change_risk_tier'))` |  |
| POST | `/admin/users/{id}/status` | `@PreAuthorize(hasAuthority('user:change_status'))` |  |

### ApprovalController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/approvals` | `@PreAuthorize(hasAuthority('approval:list'))` |  |
| POST | `/admin/approvals` | `@PreAuthorize(@authz.anyWithPrefix(authentication, 'approval:request:'))` |  |
| POST | `/admin/approvals/{id}/approve` | `@PreAuthorize(@authz.anyWithPrefix(authentication, 'approval:decide:'))` |  |
| POST | `/admin/approvals/{id}/reject` | `@PreAuthorize(@authz.anyWithPrefix(authentication, 'approval:decide:'))` |  |
| POST | `/admin/games/{id}/tos-status` | `@PreAuthorize(hasAuthority('game:change_tos'))` |  |

### AuthController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/auth/login` | Công khai (SecurityConfig) |  |
| POST | `/auth/logout` | Công khai (SecurityConfig) |  |
| POST | `/auth/refresh` | Công khai (SecurityConfig) |  |
| POST | `/auth/register` | Công khai (SecurityConfig) |  |

### DevController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/dev/jobs/run-all` | `@PreAuthorize(hasAuthority('dev:run_jobs'))` | dev |

### DevTimeController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/dev/time` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| POST | `/dev/time/advance` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| POST | `/dev/time/freeze` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| POST | `/dev/time/reset` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| POST | `/dev/time/set` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| GET | `/dev/time/upcoming` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |

### DisputeAppealController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/disputes/{id}/appeal` | `@PreAuthorize(hasAuthority('dispute:open'))` |  |
| POST | `/support/dispute-appeals/{id}/review` | `@PreAuthorize(hasAuthority('dispute:resolve'))` |  |
| GET | `/support/disputes/{id}/appeals` | `@PreAuthorize(hasAuthority('dispute:read_any'))` |  |

### DisputeController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/disputes/{id}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/disputes/{id}/attachments` | `@PreAuthorize(hasAuthority('dispute:open'))` |  |
| GET | `/disputes/{id}/attachments/{attachmentId}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/disputes/{id}/evidence` | `@PreAuthorize(hasAuthority('dispute:open'))` |  |
| GET | `/orders/{orderId}/disputes` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/orders/{orderId}/disputes` | `@PreAuthorize(hasAuthority('dispute:open'))` |  |
| GET | `/support/disputes` | `@PreAuthorize(hasAuthority('dispute:read_any'))` |  |
| POST | `/support/disputes/{id}/items/{itemId}/decide` | `@PreAuthorize(hasAuthority('dispute:resolve'))` |  |
| POST | `/support/disputes/{id}/resolve` | `@PreAuthorize(hasAuthority('dispute:resolve'))` |  |

### GameAccountController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/game-accounts` | `@PreAuthorize(hasAuthority('game_account:verify_recovery'))` |  |
| POST | `/admin/game-accounts/{id}/flag-suspicious` | `@PreAuthorize(hasAuthority('game_account:flag_suspicious'))` |  |
| POST | `/admin/game-accounts/{id}/verify-recovery` | `@PreAuthorize(hasAuthority('game_account:verify_recovery'))` |  |
| GET | `/game-accounts` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/game-accounts` | `@PreAuthorize(hasAuthority('game_account:manage_own'))` |  |
| GET | `/game-accounts/{id}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| PUT | `/game-accounts/{id}/account-key` | `@PreAuthorize(hasAuthority('game_account:manage_own'))` |  |
| POST | `/game-accounts/{id}/password-suggestion` | `@PreAuthorize(hasAuthority('game_account:manage_own'))` |  |
| POST | `/game-accounts/{id}/reclaim` | `@PreAuthorize(hasAuthority('game_account:manage_own'))` |  |
| POST | `/game-accounts/{id}/rotation` | `@PreAuthorize(hasAuthority('game_account:manage_own'))` |  |

### GameController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/admin/games` | `@PreAuthorize(hasAuthority('game:create'))` |  |
| PUT | `/admin/games/{id}` | `@PreAuthorize(hasAuthority('game:update'))` |  |
| PUT | `/admin/games/{id}/packages` | `@PreAuthorize(hasAuthority('game:update'))` |  |
| PUT | `/admin/games/{id}/risks` | `@PreAuthorize(hasAuthority('game:update'))` |  |
| GET | `/games` | Công khai (SecurityConfig) |  |
| GET | `/games/{id}` | Công khai (SecurityConfig) |  |

### HelpdeskController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/orders/{orderId}/tickets` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/support/tickets` | `@PreAuthorize(hasAuthority('helpdesk:handle'))` |  |
| POST | `/support/tickets` | `@PreAuthorize(hasAuthority('helpdesk:handle'))` |  |
| POST | `/support/tickets/{id}/assign` | `@PreAuthorize(hasAuthority('helpdesk:handle'))` |  |
| POST | `/support/tickets/{id}/participants` | `@PreAuthorize(hasAuthority('helpdesk:handle'))` |  |
| DELETE | `/support/tickets/{id}/participants/{userId}` | `@PreAuthorize(hasAuthority('helpdesk:handle'))` |  |
| GET | `/tickets` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/tickets` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/tickets/unread` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/tickets/{id}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/tickets/{id}/attachments/{attachmentId}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/tickets/{id}/messages` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/tickets/{id}/messages` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/tickets/{id}/read` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/tickets/{id}/status` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |

### LedgerAdminController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/ledger/orders` | `@PreAuthorize(hasAuthority('ledger:read'))` |  |
| GET | `/admin/ledger/orders/{orderId}` | `@PreAuthorize(hasAuthority('ledger:read'))` |  |
| GET | `/admin/ledger/reconciliation` | `@PreAuthorize(hasAuthority('ledger:read'))` |  |

### LegalNoticeController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/support/legal-notices` | `@PreAuthorize(hasAuthority('game:change_tos'))` |  |
| POST | `/support/legal-notices` | `@PreAuthorize(hasAuthority('game:change_tos'))` |  |
| GET | `/support/legal-notices/{id}` | `@PreAuthorize(hasAuthority('game:change_tos'))` |  |

### LegalNoticeTransitionController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/support/legal-notices/{id}/transition` | `@PreAuthorize(hasAuthority('game:change_tos'))` |  |

### ListingAppealController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/listing-decisions/{id}/appeals` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| GET | `/listings/{id}/decisions` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| POST | `/support/listing-appeals/{id}/review` | `@PreAuthorize(hasAuthority('listing:appeal_review'))` |  |
| GET | `/support/listing-decisions/{id}/appeals` | `@PreAuthorize(hasAuthority('listing:appeal_review'))` |  |

### ListingController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/listings` | `@PreAuthorize(hasAuthority('listing:review_queue'))` |  |
| POST | `/admin/listings/{id}/approve` | `@PreAuthorize(hasAuthority('listing:approve'))` |  |
| POST | `/admin/listings/{id}/reject` | `@PreAuthorize(hasAuthority('listing:reject'))` |  |
| POST | `/admin/listings/{id}/suspend` | `@PreAuthorize(hasAuthority('listing:suspend'))` |  |
| POST | `/listings` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| GET | `/listings/mine` | Công khai ở SecurityConfig, vì `GET /listings/*` khớp cả `/listings/mine`; controller cần người dùng đăng nhập. Hành vi khi không đăng nhập chưa kiểm chứng; không có `@PreAuthorize` |  |
| GET | `/listings/search` | Công khai (SecurityConfig) |  |
| GET | `/listings/{id}` | Công khai (SecurityConfig) |  |
| PUT | `/listings/{id}` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| GET | `/listings/{id}/busy-blocks` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| POST | `/listings/{id}/busy-blocks` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| DELETE | `/listings/{id}/busy-blocks/{blockId}` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| POST | `/listings/{id}/images` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| DELETE | `/listings/{id}/images/{imageId}` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| GET | `/listings/{id}/images/{imageId}` | Công khai (SecurityConfig) |  |
| POST | `/listings/{id}/pause` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |
| POST | `/listings/{id}/submit` | `@PreAuthorize(hasAuthority('listing:manage_own'))` |  |

### MeController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/me` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| PUT | `/me` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/me/kyc` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/me/logout-all` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/me/password` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |

### NotificationController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/notifications` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/notifications/read-all` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/notifications/unread-count` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/notifications/{id}/read` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |

### OrderController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/orders` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/orders` | `@PreAuthorize(hasAuthority('order:place'))` |  |
| GET | `/orders/{id}` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/orders/{id}/approve` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |
| POST | `/orders/{id}/cancel` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |
| GET | `/orders/{id}/credentials` | `@PreAuthorize(hasAuthority('credential:view_own'))` |  |
| POST | `/orders/{id}/credentials` | `@PreAuthorize(hasAuthority('credential:view_own'))` |  |
| GET | `/orders/{id}/extensions` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/orders/{id}/extensions` | `@PreAuthorize(hasAuthority('order:place'))` |  |
| POST | `/orders/{id}/extensions/{extensionId}/cancel` | `@PreAuthorize(hasAuthority('order:place'))` |  |
| POST | `/orders/{id}/extensions/{extensionId}/pay` | `@PreAuthorize(hasAuthority('order:place'))` |  |
| POST | `/orders/{id}/handover/confirm` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |
| POST | `/orders/{id}/otp-note` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |
| POST | `/orders/{id}/otp-request` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |
| POST | `/orders/{id}/pay` | `@PreAuthorize(hasAuthority('order:place'))` |  |
| POST | `/orders/{id}/reject` | `@PreAuthorize(hasAuthority('order:manage_own'))` |  |

### PaymentController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/dev/payments/{intentId}/simulate-success` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` | dev |
| GET | `/payments/providers` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/payments/return/{provider}` | Công khai (SecurityConfig) |  |
| GET | `/payments/webhook/{provider}` | Công khai (SecurityConfig) |  |
| POST | `/payments/webhook/{provider}` | Công khai (SecurityConfig) |  |

### SettingsController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/admin/settings` | `@PreAuthorize(hasAuthority('setting:read'))` |  |
| PUT | `/admin/settings` | `@PreAuthorize(hasAuthority('setting:update'))` |  |
| GET | `/settings/public` | Công khai (SecurityConfig) |  |

### StaffUserController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/staff/users` | `@PreAuthorize(StaffUserController.ANY_STAFF_LOOKUP)` |  |
| GET | `/staff/users/brief` | `@PreAuthorize(StaffUserController.ANY_STAFF_LOOKUP)` |  |

### TotpController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| POST | `/me/mfa/totp/confirm` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/me/mfa/totp/start` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |

### WalletController

| Phương thức | Đường dẫn | Điều kiện | Chỉ dev |
|---|---|---|---|
| GET | `/finance/withdrawals` | `@PreAuthorize(hasAuthority('withdrawal:list_all'))` |  |
| POST | `/finance/withdrawals/{id}/mark-failed` | `@PreAuthorize(hasAuthority('withdrawal:mark_failed'))` |  |
| POST | `/finance/withdrawals/{id}/mark-paid` | `@PreAuthorize(hasAuthority('withdrawal:mark_paid'))` |  |
| GET | `/wallet/balances` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/wallet/deposits` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/wallet/topups` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/wallet/topups` | `@PreAuthorize(hasAuthority('wallet:withdraw'))` |  |
| GET | `/wallet/transactions` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| GET | `/wallet/withdrawals` | Đăng nhập (SecurityConfig `/api/v1/**`); không có `@PreAuthorize` |  |
| POST | `/wallet/withdrawals` | `@PreAuthorize(hasAuthority('wallet:withdraw'))` |  |

## Liên quan

- `docs/RentGG_System_Design_v1.2.md` §11.2: danh sách một phần, mô tả ý định.
- `docs/RentGG_Staff_Guide.md`: quyền theo vai trò.
- `docs/RentGG_Operations_Runbook.md`: endpoint vận hành.
