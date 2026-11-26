# RentGG — Hướng dẫn nhân viên (Hỗ trợ, Quản trị, Tài chính)

Tài liệu này dành cho nhân viên dùng trang quản trị. Quyền được lấy từ dữ liệu RBAC trong migration và từ guard trong giao diện. Mục nào không có nguồn trong repo được ghi **chưa kiểm chứng**.

Lưu ý: guard trong giao diện chỉ ẩn trang. Việc chặn ở API đi theo quyền trong DB. Chưa đối chiếu từng endpoint với quyền trong lượt này.

## 1. Vai trò

Nguồn: `backend/src/main/resources/db/migration/V8__rbac_roles_permissions.sql` (bảng `roles`).

| Mã | Tên | Mô tả trong migration |
|---|---|---|
| `USER` | Người dùng | Vai trò mặc định của mọi tài khoản |
| `SUPPORT` | Hỗ trợ | Xử lý khiếu nại, người dùng, kiểm duyệt cơ bản |
| `ADMIN` | Quản trị | Quản trị nền tảng |
| `FINANCE` | Tài chính | Chi trả, đối soát, điều chỉnh sổ cái |

Mã quyền được khai báo trong code (`backend/src/main/java/com/rentgg/shared/security/Permission.java`) và được đồng bộ vào bảng `permissions` khi khởi động (`backend/src/main/java/com/rentgg/identity/application/PermissionCatalogSync.java`). Việc gán quyền cho vai trò nằm trong bảng `role_permissions`, đổi được trong trang Vai trò. Trang Vai trò (`/admin/roles`, `frontend/src/App.tsx`) được bảo vệ bằng `role:read`. Quyền sửa vai trò trong V8 là `role:manage`. Một người có thể có nhiều vai trò (`user_roles`).

Migration V8 seed các quyền ban đầu. Các migration sau thêm quyền cho vai trò: `V22` (`setting:read`, `setting:update`), `V28` (`helpdesk:handle`), `V32` (`audit:read`), `V36` (`listing:appeal_review`). Đã đối chiếu: mọi mã quyền viết thẳng trong `Permission.java` đều xuất hiện trong migration; các mã `approval:request:<loại>` và `approval:decide:<loại>` được ghép từ tiền tố và tên loại trong hàm của `Permission.java`. Lượt này không đối chiếu quyền nào được gán cho vai trò mà không có trong migration.

## 2. Màn hình và quyền truy cập

Nguồn: `frontend/src/App.tsx` (`RequirePermission anyOf=...`). Người dùng cần **một trong** các mã trong `anyOf`.

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

Nguồn: `V8__rbac_roles_permissions.sql` và các migration bổ sung ở mục 1. Bảng này chỉ tóm tắt các quyền liên quan tới công việc nhân viên.

| Vai trò | Quyền chính |
|---|---|
| `SUPPORT` | Xem tin và đơn (`listing:read_any`, `order:read_any`), hàng đợi tin (`listing:review_queue`), khiếu nại (`dispute:read_any`, `dispute:resolve`), xem và đổi trạng thái người dùng (`user:read`, `user:change_status`, `user:change_risk_tier`, `user:read_abuse_signals`), đánh dấu tài khoản game đáng ngờ (`game_account:flag_suspicious`), gửi yêu cầu `ACCOUNT_SECURITY_RESTORE` và `SELLER_HOLD_EARLY_RELEASE`, hỗ trợ (`helpdesk:handle`) |
| `ADMIN` | Gồm hầu hết quyền của SUPPORT, cộng: quản lý game (`game:create`, `game:update`, `game:change_tos`), duyệt/từ chối/đình chỉ tin, xác minh khôi phục và KYC (`game_account:verify_recovery`, `user:kyc_decide`), xem danh sách rút tiền (`withdrawal:list_all`), sổ cái (`ledger:read`), quản lý vai trò (`role:manage`, `role:assign`), cấu hình (`setting:update`), tra cứu audit (`audit:read`), duyệt cả bốn loại yêu cầu phê duyệt (mục 4), và `dev:run_jobs` (chỉ có ý nghĩa ở dev) |
| `FINANCE` | Xem tin (`listing:read_any`), danh sách rút tiền, xem thông tin ngân hàng khi rút (`withdrawal:read_bank_details`), đánh dấu đã chi hoặc chi thất bại (`withdrawal:mark_paid`, `withdrawal:mark_failed`), sổ cái (`ledger:read`), gửi và duyệt yêu cầu `SELLER_HOLD_EARLY_RELEASE` và `LEDGER_ADJUSTMENT`, xem cấu hình (`setting:read`) |

Theo V8, các vai trò SUPPORT, ADMIN và FINANCE không có `credential:view_own` (quyền xem thông tin đăng nhập của đơn đang thuê). Vai trò USER là mặc định của mọi tài khoản và có quyền này, nên tài khoản nhân viên vẫn giữ nó qua vai trò USER. `ADMIN` không có `withdrawal:read_bank_details`.

## 4. Phê duyệt kép

Nguồn: bảng `approval_requests` (`backend/src/main/resources/db/migration/V5__dispute_withdrawal_approval.sql`). Bảng có:

- trạng thái `PENDING`, `APPROVED`, `REJECTED`, `EXECUTED`;
- ràng buộc `ck_approval_four_eyes`: `approved_by IS NULL OR approved_by <> requested_by`. Người duyệt không thể là người tạo yêu cầu, ràng buộc này nằm trong DB.

Loại yêu cầu (`action_type`) và quyền tương ứng lấy từ V8:

| Loại | Ai tạo | Ai duyệt |
|---|---|---|
| `GAME_ALLOW` | `ADMIN` | `ADMIN` |
| `ACCOUNT_SECURITY_RESTORE` | `SUPPORT`, `ADMIN` | `ADMIN` |
| `SELLER_HOLD_EARLY_RELEASE` | `SUPPORT`, `ADMIN`, `FINANCE` | `ADMIN`, `FINANCE` |
| `LEDGER_ADJUSTMENT` | `FINANCE` | `ADMIN`, `FINANCE` |

Kiểm thử tay đã thấy: admin tự duyệt yêu cầu của mình bị từ chối với HTTP 403, admin khác duyệt thì được (`A13`, `A13-fix` trong `docs/RentGG_Manual_Test_Report.md`, xem mục 6). Đưa TOS của game về `ALLOWED` cũng đi qua duyệt kép: API trả 202 và tạo yêu cầu, admin thứ hai duyệt thì trạng thái chuyển `EXECUTED` (`A40`).

## 5. Dữ liệu nhạy cảm

- Thông tin đăng nhập của account game: người thuê xem qua `GET /orders/{id}/credentials`, chỉ trong cửa sổ thuê. Giới hạn số lượt xem là `rentgg.credentials.rate-limit-per-hour` (mặc định 10 theo `docs/RentGG_System_Design_v1.2.md` mục 14.2; **chưa kiểm chứng** giá trị trong `application.yml`).
- Thông tin ngân hàng khi rút tiền: theo V8 chỉ `FINANCE` có `withdrawal:read_bank_details`.
- Xác thực hai bước: `StartupSafetyCheck` từ chối khởi động trên staging/prod khi `RENTGG_STAFF_MFA_REQUIRED` không phải `true`. Phạm vi vai trò bị ép MFA (`SUPPORT`, `ADMIN`, `FINANCE`) theo `docs/RentGG_Dev_Guide.md` mục 2.4. **Chưa kiểm chứng** trong code từ phía bộ lọc xác thực. Kiểm thử tay `A22` ghi: đăng nhập không có mã bị từ chối với `MFA_REQUIRED`. Bật TOTP cho tài khoản nhân viên (`MFA1`) **chưa thực hiện**, bị chặn (mục 6).

## 6. Thao tác đã kiểm thử tay theo vai trò

Các ID dưới đây là hàng trong ma trận kiểm thử tay (`docs/RentGG_Manual_Test_Report.md`, tạo cùng lượt này). Ma trận ghi kết quả từ lần chạy thật trên backend dev. Trạng thái và bằng chứng xem trong báo cáo.

Ghi chú: ma trận ghi tên tài khoản thực hiện khi có. Với các dòng không ghi, vai trò được suy từ quyền trong V8 và ghi rõ ở đây.

**Hỗ trợ (`SUPPORT`)**
- Người dùng: `A08` tra cứu theo ID, `A09` đổi trạng thái (có audit), `A10` đổi mức rủi ro. Quyền `user:read`, `user:change_status`, `user:change_risk_tier` có ở cả SUPPORT và ADMIN.
- Gắn cờ tài khoản game đáng ngờ với lý do: `A07`. Quyền `game_account:flag_suspicious` có ở cả SUPPORT và ADMIN.
- Ticket hỗ trợ: `A16` mở và trả lời (ghi là hỗ trợ); `A59` phân công người xử lý (ghi là support). `A55`–`A58` (đánh dấu đã đọc, đổi trạng thái, thêm và gỡ người tham gia) không ghi người thực hiện; quyền `helpdesk:handle` có ở SUPPORT và ADMIN.
- Khiếu nại của người thuê: `A74` quyết định từng mục (chấp nhận hoặc bác); `B06` chấp nhận và hoàn tiền thuê; `B06r` bác khiếu nại (ghi là hỗ trợ); `B06a-fix3` khiếu nại `RENTER_LOGIN_FAILED` không hiện ô khấu trừ cọc.
- Yêu cầu phê duyệt của chính mình: `FX3` (tài khoản `support@`) không có nút Duyệt, chỉ có Từ chối.

**Quản trị (`ADMIN`)**
- Duyệt tin: `A04` từ chối có lý do (chủ thấy lý do), `A05` duyệt bài chờ, `A36` đình chỉ tin (quyền `listing:suspend` chỉ ADMIN có theo V8), `A69` chủ tạo tin có ảnh rồi admin duyệt.
- Khôi phục account: `A06` xác minh khôi phục.
- Game: `A11`, `A11-fix` tạo game và sửa phí nền tảng; `A39` sửa rủi ro và gói; `A40` đổi TOS qua duyệt kép.
- Duyệt kép: `A13`, `A13-fix` (người thứ hai duyệt, người tạo bị 403); `A78` từ chối yêu cầu.
- Vai trò: `A14` xem chỉ đọc; `A30`, `A31` gán và gỡ vai trò; `A32`–`A35` tạo, sửa quyền, bật cờ nhân viên, xoá vai trò.
- Cấu hình: `A38` lưu giá trị cấu hình.
- KYC: `A75` duyệt, `A76` từ chối.

**Tài chính (`FINANCE`)**
- Đối soát: `A17`, `A17-fix` (sổ cái cân bằng). Quyền `ledger:read` cũng có ở ADMIN; ma trận không ghi tài khoản thực hiện. Hàng A17 còn ghi finance nhận 403 từ `GET /api/v1/games`, chưa điều tra.
- Rút tiền: `A71` finance đánh dấu thất bại với lý do, số dư khả dụng được hoàn. `B09` đánh dấu đã chi (quyền `withdrawal:mark_paid` chỉ FINANCE có theo V8; ma trận không ghi tài khoản). Phía chủ account gửi yêu cầu rút là `B08`, không phải thao tác của nhân viên.
- Mở màn duyệt không gọi API bị cấm: `FX2`, `FX4-finance`.

**Kiểm tra phân quyền chung:** `A02` mỗi vai đăng nhập thấy đúng menu; `A03` người thuê không vào được trang quản trị.

**Chưa kiểm thử hoặc bị chặn (không có kết quả để dạy nhân viên):** `MFA1` bật TOTP cho nhân viên (bị chặn); `C01` PSP thật; `C02` R2 thật; `C03` email (chưa có tính năng); `C04` app iOS/Android (không có simulator). Chi tiết lý do ở báo cáo kiểm thử.
