# Cơ sở dữ liệu

Ngày kiểm tra **2026-11-21**, theo `backend/src/main/resources/db/migration/`. Schema được tạo và cập nhật bởi Flyway; không sửa schema tay.

## Tóm tắt

| Mục | Giá trị (đã kiểm tra) | Lệnh kiểm tra |
|---|---|---|
| Engine | PostgreSQL 16 | `postgres:16` trong `docker-compose.yml`, `.github/workflows/backend.yml` |
| Số file migration | 37 (V1–V37) | `ls backend/src/main/resources/db/migration \| wc -l` |
| Số lệnh `CREATE TABLE` | 42, nằm trong 19 file | `grep -c "CREATE TABLE" backend/src/main/resources/db/migration/*.sql` |
| Migration cuối | `V37__legal_notice_lifecycle.sql` | `ls backend/src/main/resources/db/migration \| tail -1` |
| Migration mới | Số tiếp theo là **V38** | Xem [mục migration](#migration) |

Ghi chú: `pricing_tiers` (V12) và `suggested_packages` (V23) là cột thêm bằng `ALTER TABLE`, không phải bảng riêng. Đếm 42 tính theo `CREATE TABLE`.

## Nhóm bảng theo module

Tên bảng lấy từ `CREATE TABLE` trong migration. Mỗi bảng ghi một lần ở đây; module nào sở hữu xem [MODULES.md](MODULES.md).

| Nhóm | Bảng |
|---|---|
| Identity, RBAC, MFA | `users`, `user_roles`, `refresh_tokens`, `roles`, `permissions`, `role_permissions`, `user_totp` |
| Catalog, cài đặt | `games`, `app_settings`, `game_risks` |
| Account game | `game_accounts`, `credential_versions`, `account_blocks`, `abuse_signals` |
| Listing | `listings`, `listing_images`, `listing_moderation_decisions`, `listing_appeals` |
| Đơn và cọc | `orders`, `order_extensions`, `deposits` |
| Thanh toán | `payment_intents`, `refunds` |
| Sổ kế toán | `ledger_accounts`, `ledger_transactions`, `ledger_postings` |
| Ví | `withdrawals` |
| Khiếu nại | `disputes`, `dispute_items`, `dispute_attachments`, `dispute_appeals` |
| Phê duyệt | `approval_requests` |
| Hỗ trợ | `support_tickets`, `support_ticket_participants`, `support_messages`, `support_attachments` |
| Pháp lý | `legal_notices` |
| KYC | `kyc_submissions` |
| Thông báo, audit, job | `notifications`, `audit_logs`, `outbox_events`, `shedlock` |

Bảng trên là đủ 42 bảng khi đối chiếu với `grep -ho "CREATE TABLE [a-z_]*" backend/src/main/resources/db/migration/*.sql` (kiểm tra 2026-11-21). Khi thêm bảng mới, cập nhật lại nhóm này.

## Bất biến ở tầng database

Đã có trong migration (IMPLEMENTED):

- **Append-only** (`V7__append_only_triggers.sql`): trigger chặn `UPDATE`, `DELETE` và `TRUNCATE` trên `audit_logs`, `ledger_transactions`, `ledger_postings`. Lỗi trả về là `Table ... is append-only`.
- **Sổ cân bằng** (`V7`): constraint trigger `trg_ledger_balanced` (`AFTER INSERT ON ledger_postings`, `DEFERRABLE INITIALLY DEFERRED`) gọi `check_ledger_tx_balanced()`. Khi commit, tổng `amount` của các posting cùng `transaction_id` phải bằng 0, và phải có ít nhất 2 posting. Lỗi trả về là `check_violation`.
- **Đơn không chồng khoảng thuê trên cùng account**: exclusion constraint `ex_orders_no_overlap` (`EXCLUDE USING gist (game_account_id WITH =, rental_period WITH &&)`). Được tạo ở `V3` và thay bằng định nghĩa cuối cùng ở `V14`, áp dụng khi `status` thuộc `PENDING_PAYMENT`, `AWAITING_OWNER_APPROVAL`, `CONFIRMED`, `IN_RENTAL`. `rental_period` là cột sinh tự động (`V14`), gồm cả thời gian gia hạn đang được giữ.
- **Lịch chặn của account không chồng nhau** (`V26`): exclusion constraint `ex_account_blocks_no_overlap` trên `account_blocks`.
- **Đơn không giao với lịch chặn của account** (`V26`): trigger `trg_orders_account_blocks` gọi `orders_check_account_blocks()`. Hàm khóa lịch của account, rồi báo lỗi nếu `rental_period` của đơn giao với `account_blocks.period` cùng account. Đơn đã giữ chỗ và chỉ thu hẹp khoảng thuê thì bỏ qua kiểm tra.
- **Duyệt không tự phê duyệt** (`V5`): `approval_requests` có `CONSTRAINT ck_approval_four_eyes CHECK (approved_by IS NULL OR approved_by <> requested_by)`.
- Kiểm tra toàn bộ ràng buộc: `grep -n "EXCLUDE\|ex_orders_no_overlap\|CHECK\|TRIGGER" backend/src/main/resources/db/migration/*.sql`.

## Migration

### Quy tắc hiện hành

- Không sửa migration đã áp dụng (V1 đến V37). Thay đổi bằng file mới.
- Tên file: `V<số>__<mô_tả_snake_case>.sql`.
- Số tiếp theo: lấy số lớn nhất hiện có + 1. Hiện tại là V38.
- Mỗi thời điểm chỉ một người hoặc một agent tạo migration mới (theo `CLAUDE.md` và `AGENTS.md`).

### Lệch giữa tài liệu và thực tế

`CLAUDE.md` và `AGENTS.md` ghi "không sửa V1–V30" và "migration mới đánh số từ V31". Thực tế đã có V1–V37, trong đó V31–V37 đã được áp dụng. Tài liệu này ghi theo thực tế. Chưa đổi quy tắc trong `CLAUDE.md` và `AGENTS.md` ở đây, vì quy tắc đó thuộc phạm vi người quản lý dự án quyết định. Xem [DECISIONS.md](DECISIONS.md).

### Kiểm tra

- Test `backend/src/test/java/com/rentgg/` dùng database `rentgg_test`. Flyway chạy khi test khởi động.
- Lệnh chạy migration trên database dev: khởi động backend với profile `dev` (Flyway chạy khi khởi động).
- Chạy migration trên production: xem [RentGG_Production_Deploy.md](RentGG_Production_Deploy.md).

## Dữ liệu mẫu

- Profile `dev` có `DevDataSeeder` (`backend/src/main/java/com/rentgg/jobs/DevDataSeeder.java`), chỉ bật ở profile `dev`.
- Mật khẩu tài khoản mẫu là hằng `DevDataSeeder.DEV_PASSWORD`. Tài liệu này không ghi giá trị. Xem [DEVELOPMENT.md](DEVELOPMENT.md#tài-khoản-mẫu).

## Ràng buộc và lưu ý khi thay đổi schema

- Đổi cột đang có dữ liệu thật cần migration bảo toàn dữ liệu. Chưa có quy trình được ghi lại cho việc này.
- Bảng append-only không cho `UPDATE` hay `DELETE`. Không tìm thấy logic bút toán đảo trong `ledger/` (kiểm tra 2026-11-21), nên cách sửa sai dữ liệu sổ là UNKNOWN.
- Sau khi thêm bảng mới, cập nhật danh sách ở mục trên.
