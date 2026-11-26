# RentGG — Bổ sung từ điển dữ liệu

Tài liệu này chỉ ghi phần **không có** trong `RentGG_System_Design_v1.2.md` §5–§6, hoặc khác với đó. Cột và kiểu của các bảng còn lại xem §5 và §6 của thiết kế. Nguồn: migration trong `backend/src/main/resources/db/migration/` và mã trong `backend/src/main/java/`. Mục nào chưa đối chiếu được ghi rõ.

## 1. Bảng tạo bởi migration nhưng không có trong §5

Danh sách lấy từ lệnh `CREATE TABLE` trong các migration V1–V37. Đối chiếu với các tiêu đề in đậm `**tên_bảng**` trong §5. Có thể bỏ sót nếu §5 mô tả bảng theo cách khác.

| Bảng | Migration | Ghi chú |
|---|---|---|
| `listing_images` | V9 | Ảnh listing |
| `roles`, `permissions`, `role_permissions` | V8 | RBAC |
| `dispute_attachments` | V13 | Bằng chứng khiếu nại |
| `order_extensions` | V14 | Gia hạn đơn |
| `game_risks` | V17 | Rủi ro theo game |
| `app_settings` | V22 | Cấu hình hệ thống |
| `dispute_items` | V24 | Mục rủi ro của khiếu nại |
| `account_blocks` | V26 | Lịch bận của account |
| `support_tickets`, `support_messages`, `support_ticket_participants`, `support_attachments` | V28 | Hỗ trợ |
| `user_totp` | V31 | Xác thực hai bước cho nhân viên |
| `dispute_appeals` | V33 | Kháng nghị khiếu nại |
| `legal_notices` | V34 | Thông báo pháp lý |
| `listing_appeals`, `listing_moderation_decisions` | V36 | Kháng nghị kiểm duyệt listing |

Các cột thêm bằng `ALTER TABLE` (ví dụ `game_accounts.login_fingerprint` V11, `listings.pricing_tiers` V12, `orders.extension_hold_until` và `orders.extended_minutes` V14) không được liệt kê hết ở đây. Muốn đầy đủ, đọc từng migration.

Tên bảng trong §5 khớp với migration cho các bảng: `users`, `refresh_tokens`, `kyc_submissions`, `abuse_signals`, `game_accounts`, `credential_versions`, `listings`, `orders`, `deposits`, `payment_intents`, `refunds`, `disputes`, `withdrawals`, `ledger_accounts`, `ledger_transactions`, `ledger_postings`, `approval_requests`, `audit_logs`, `notifications`, `outbox_events`, `shedlock`.

## 2. Quy ước sổ cái

- `ledger_postings.amount` là `bigint`, có ràng buộc `amount <> 0` (`V4__ledger.sql`).
- Trong mã, `Posting.debit(...)` ghi giá trị **dương**; `Posting.credit(...)` ghi giá trị **âm** (`backend/src/main/java/com/rentgg/ledger/domain/Posting.java`). Quy ước là debit dương.
- Chưa đối chiếu: ràng buộc tổng các bút toán trong một giao dịch bằng 0 có được kiểm trong DB hay trong mã hay không.

## 3. Tài khoản `SELLER_AVAILABLE` dùng cho ví người thuê

Khi hoàn tiền thành công, `PaymentService.processRefund` ghi bút toán `REFUND_COMPLETED`: debit `REFUND_PAYABLE`, credit `SELLER_AVAILABLE` với `owner_user_id` là người đã trả tiền (`backend/src/main/java/com/rentgg/payment/application/PaymentService.java`, khoảng dòng 387–392). Nạp ví cũng ghi vào tài khoản này. Vì vậy số dư ví của người thuê nằm trong `SELLER_AVAILABLE`, không có tài khoản ví riêng. Tên tài khoản dễ gây hiểu nhầm.

Đây là phần liên quan tới câu hỏi hoàn tiền về ví thay vì về PSP. Xem `docs/README.md`, mục Mâu thuẫn số 3.

## 4. Thứ tự `created_at` không khớp thứ tự commit

Trong ví và sổ cái, cột `created_at` của hai bút toán có thể đảo ngược so với thứ tự chúng được commit. Ví dụ đã thấy: một hoàn tiền có `created_at` muộn hơn nhưng được commit trước một thanh toán có `created_at` sớm hơn. Hệ quả: báo cáo sắp theo `created_at` có thể hiện số dư âm trong khi số dư thật không bao giờ âm. Khi cần số dư đúng, tính theo thứ tự commit (ví dụ dùng `xmin` của hàng, như đã làm trong kiểm thử). Cách tính số dư theo thiết kế xem §7.7. Nguồn: `docs/RentGG_Manual_Test_Report.md` mục 3.3.

## 5. Trạng thái cọc (`deposits.status`)

Các giá trị trong `CHECK` của migration: `PENDING_PAYMENT`, `HELD`, `POST_RENTAL_HOLD`, `DISPUTED`, `REFUND_PENDING`, `REFUNDED`, `SETTLED`, `VOID`. Các chuyển trạng thái đã đối chiếu với `backend/src/main/java/com/rentgg/deposit/application/DepositService.java` và khớp với §6.2:

- `HELD` → `REFUND_PENDING` khi hủy hoặc từ chối trước khi thuê (`markRefundOnCancel`).
- `HELD` → `POST_RENTAL_HOLD` khi đơn kết thúc (`startPostRentalHold`).
- `HELD` hoặc `POST_RENTAL_HOLD` → `DISPUTED` khi mở khiếu nại (`markDisputed`).
- `DISPUTED` → `SETTLED` khi có khấu trừ > 0; `DISPUTED` → `REFUND_PENDING` khi không khấu trừ (`settleDispute`).
- `POST_RENTAL_HOLD` → `REFUND_PENDING` khi quá `hold_until` và không có khiếu nại (`releaseOne`).
- `REFUND_PENDING` → `REFUNDED` khi nhận `RefundCompleted` với `kind = DEPOSIT` và số còn lại bằng 0 (`onRefundCompleted`).

Ghi chú: trạng thái `SETTLED` vẫn có thể còn phần hoàn qua `refunds` (kind `DEPOSIT`). §6.2 nói điều này.

## 6. Các cửa sổ thời gian

- **Cửa sổ khiếu nại của người thuê** (`RENTER_LOGIN_FAILED`, `RENTER_NOT_AS_DESCRIBED`): đóng sau `complaint_window_minutes` kể từ `credential_first_viewed_at`, hoặc ngay khi đơn có `handover_confirmed_at`. Lỗi trả về `COMPLAINT_WINDOW_CLOSED` (`backend/src/main/java/com/rentgg/dispute/application/DisputeService.java`, khoảng dòng 198–217). Khớp với §6.5 (dòng 814) và §5.2 (dòng 441).
- **Cửa sổ khiếu nại rủi ro của chủ** (`OWNER_RISK`): đóng sau `complaint_window_minutes` kể từ `actual_end_at` (khoảng dòng 218–229).
- **Cửa sổ xem thông tin đăng nhập**: lỗi `CREDENTIAL_WINDOW_CLOSED` trong `backend/src/main/java/com/rentgg/order/application/HandoverService.java` (khoảng dòng 113). §12.4 của thiết kế quy định `now ∈ [start_at, block_end_at]`. Chưa đối chiếu giới hạn trong mã với mốc này.

## 7. Phần còn lại

Mọi cột, kiểu, khóa ngoại và chỉ mục của các bảng trong §5 xem `docs/RentGG_System_Design_v1.2.md` §5. Trạng thái đơn và các máy trạng thái khác xem §6. Tài liệu này không thay thế hai mục đó.
