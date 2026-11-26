# RentGG — Hướng dẫn nhân viên (Hỗ trợ, Quản trị, Tài chính)

Tài liệu này dành cho nhân viên dùng trang quản trị. Quyền được lấy từ dữ liệu RBAC trong migration và từ guard trong giao diện. Mọi thứ không có nguồn trong repo được ghi **chưa kiểm chứng**.

Lưu ý: guard trong giao diện chỉ ẩn trang. Việc chặn ở API đi theo quyền trong DB và `SecurityConfig`. Mục này chưa đối chiếu từng endpoint với quyền.

## 1. Vai trò

Nguồn: `backend/src/main/resources/db/migration/V8__rbac_roles_permissions.sql` (bảng `roles`).

| Mã | Tên | Mô tả trong migration |
|---|---|---|
| `USER` | Người dùng | Vai trò mặc định của mọi tài khoản |
| `SUPPORT` | Hỗ trợ | Xử lý khiếu nại, người dùng, kiểm duyệt cơ bản |
| `ADMIN` | Quản trị | Quản trị nền tảng |
| `FINANCE` | Tài chính | Chi trả, đối soát, điều chỉnh sổ cái |

Quyền không nằm cứng trong code. Bảng `permissions` và `role_permissions` lưu chúng. Trang Vai trò (`/admin/roles`, `frontend/src/pages/RolesPage.tsx`) được bảo vệ bằng `role:read`. Quyền sửa vai trò trong V8 là `role:manage`. Một người có thể có nhiều vai trò (`user_roles`).

Các migration sau V8 cũng thêm quyền cho vai trò: `V22` (`setting:*`), `V28` (`helpdesk:handle`), `V32` (`audit:read`), `V36` (`listing:appeal_review`). Danh sách quyền đầy đủ là kết quả của mọi migration này, không phải chỉ V8.

## 2. Màn hình và quyền truy cập

Nguồn: `frontend/src/App.tsx` (`RequirePermission anyOf=...`). Quyền là **một trong các** mã trong `anyOf`.

| Đường dẫn | Trang | Quyền cần (anyOf) | Ghi chú |
|---|---|---|---|
| `/admin/listings` | `AdminListingsPage` | `listing:review_queue` | Hàng đợi duyệt tin |
| `/support/disputes` | `SupportDisputesPage` | `dispute:read_any` | Hàng đợi khiếu nại |
| `/admin/games` | `AdminGamesPage` | `game:read_hidden` | Cấu hình game |
| `/admin/users` | `AdminUsersPage` | `user:read` | Hồ sơ và trạng thái người dùng |
| `/admin/approvals` | `ApprovalsPage` | `approval:list` hoặc `game:change_tos` | Yêu cầu phê duyệt |
| `/finance` | `FinancePage` | `withdrawal:list_all` hoặc `ledger:read` | Rút tiền, sổ cái |
| `/admin/roles` | `RolesPage` | `role:read` | Vai trò và quyền |
| `/admin/settings` | `AdminSettingsPage` | `setting:read` | Cấu hình hệ thống |
| `/tickets`, `/tickets/:id` | `TicketsPage` | Chỉ đăng nhập (`RequireAuth`) | Quyền xử lý hỗ trợ là `helpdesk:handle`. **Chưa kiểm chứng** trang này ẩn nút theo quyền nào |

Trang dành cho người dùng thường (đơn, ví, tin của tôi, hồ sơ, thông báo) không nằm trong bảng này.

## 3. Quyền theo vai trò

Nguồn: `V8__rbac_roles_permissions.sql` và các migration bổ sung ở mục 1. Bảng này chỉ tóm tắt các quyền liên quan tới công việc nhân viên. Muốn biết đầy đủ, đọc migration.

| Vai trò | Quyền chính |
|---|---|
| `SUPPORT` | Xem tin và đơn (`listing:read_any`, `order:read_any`), hàng đợi tin (`listing:review_queue`), khiếu nại (`dispute:read_any`, `dispute:resolve`), xem và đổi trạng thái người dùng (`user:read`, `user:change_status`, `user:change_risk_tier`, `user:read_abuse_signals`), đánh dấu tài khoản game đáng ngờ (`game_account:flag_suspicious`), gửi yêu cầu `ACCOUNT_SECURITY_RESTORE` và `SELLER_HOLD_EARLY_RELEASE`, hỗ trợ (`helpdesk:handle`) |
| `ADMIN` | Gồm hầu hết quyền của SUPPORT, cộng: quản lý game (`game:create`, `game:update`, `game:change_tos`), duyệt/từ chối/đình chỉ tin, xác minh khôi phục và KYC (`game_account:verify_recovery`, `user:kyc_decide`), xem danh sách rút tiền (`withdrawal:list_all`), sổ cái (`ledger:read`), quản lý vai trò (`role:manage`, `role:assign`), cấu hình (`setting:update`), tra cứu audit (`audit:read`), duyệt cả bốn loại yêu cầu phê duyệt (mục 4), và `dev:run_jobs` (chỉ có ý nghĩa ở dev) |
| `FINANCE` | Xem tin (`listing:read_any`), danh sách rút tiền, xem thông tin ngân hàng khi rút (`withdrawal:read_bank_details`), đánh dấu đã chi hoặc chi thất bại (`withdrawal:mark_paid`, `withdrawal:mark_failed`), sổ cái (`ledger:read`), gửi và duyệt yêu cầu `SELLER_HOLD_EARLY_RELEASE` và `LEDGER_ADJUSTMENT`, xem cấu hình (`setting:read`) |

Quyền không cấp cho nhân viên theo V8: `credential:view_own` (chỉ người thuê xem thông tin đăng nhập của đơn mình đang thuê) và `withdrawal:read_bank_details` cho `ADMIN` (chỉ `FINANCE`).

## 4. Quy tắc phê duyệt

Nguồn: bảng `approval` (`V5__dispute_withdrawal_approval.sql`) có `requested_by` và `decided_by`. Loại yêu cầu và quyền tương ứng lấy từ V8:

| Loại | Ai tạo | Ai duyệt |
|---|---|---|
| `GAME_ALLOW` | `ADMIN` | `ADMIN` |
| `ACCOUNT_SECURITY_RESTORE` | `SUPPORT`, `ADMIN` | `ADMIN` |
| `SELLER_HOLD_EARLY_RELEASE` | `SUPPORT`, `ADMIN`, `FINANCE` | `ADMIN`, `FINANCE` |
| `LEDGER_ADJUSTMENT` | `FINANCE` | `ADMIN`, `FINANCE` |

Người duyệt phải khác người tạo. **Chưa kiểm chứng**: ràng buộc này có được thực thi trong DB (chưa đọc constraint trong migration) hay chỉ trong code. Nếu cần khẳng định, đọc `ApprovalService` và migration tương ứng trước.

## 5. Dữ liệu nhạy cảm

- Thông tin đăng nhập của account game không hiện cho nhân viên qua quyền trong V8. Người thuê xem qua `GET /orders/{id}/credentials`, chỉ trong cửa sổ thuê, và có giới hạn lượt xem (`rentgg.credentials.rate-limit-per-hour`, mặc định 10, theo `docs/RentGG_System_Design_v1.2.md` §14.2).
- Thông tin ngân hàng khi rút tiền chỉ `FINANCE` có quyền xem đầy đủ (`withdrawal:read_bank_details`).
- Xác thực hai bước: `StartupSafetyCheck` từ chối khởi động trên staging/prod khi `RENTGG_STAFF_MFA_REQUIRED` không phải `true`. Phạm vi vai trò bị ép MFA (`SUPPORT`, `ADMIN`, `FINANCE`) theo `docs/RentGG_Dev_Guide.md` mục 2.4. **Chưa kiểm chứng** trong code từ phía bộ lọc xác thực.

## 6. Quy trình theo vai trò

Chưa viết trong repo. Các bước đã kiểm chứng bằng test nằm ở `docs/RentGG_System_Design_v1.2.md` mục 8 (luồng kỹ thuật) và `docs/RentGG_Use_Cases_China_Model_v8.md` mục 5 (đặc tả use case). Mục này sẽ được bổ sung khi có kết quả kiểm thử tay, theo `scratchpad` của phiên kiểm thử. **Chưa kiểm chứng** các bước thao tác trên giao diện theo từng vai trò.
