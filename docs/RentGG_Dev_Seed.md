# RentGG: dữ liệu seed cho môi trường dev

Seeder: `backend/src/main/java/com/rentgg/jobs/DevDataSeeder.java`. Chỉ chạy với profile `dev`, và chỉ chạy khi DB chưa có `admin@rentgg.dev`. Muốn seed lại thì phải xoá DB dev (ví dụ `docker compose down -v`), rồi khởi động lại backend.

Mọi dữ liệu đều được tạo qua service thật (đăng ký, tạo listing, đặt đơn, thanh toán qua FAKE PSP), không chèn SQL trực tiếp. Vì vậy ledger, audit, outbox và notification đều nhất quán.

Mật khẩu chung của mọi user seed: `RentGG-dev-2026!`

## 1. Dữ liệu đã seed

### User

| Email | Vai trò / trạng thái | Dùng để |
|---|---|---|
| admin@rentgg.dev | ADMIN | Duyệt listing, đổi ToS game, tạo GAME_ALLOW |
| admin2@rentgg.dev | ADMIN | Người duyệt thứ hai cho dual approval |
| support@rentgg.dev | SUPPORT | Xử lý dispute, đã tạo 2 yêu cầu phê duyệt |
| finance@rentgg.dev | FINANCE | Duyệt rút tiền, duyệt giải phóng doanh thu sớm |
| owner@rentgg.dev | User, rủi ro STANDARD | Chủ account chính |
| owner2@rentgg.dev | User, rủi ro **HIGH** | Chủ account rủi ro cao (giữ doanh thu 168h ở game mẫu) |
| renter@rentgg.dev | User | Người thuê chính |
| renter2@rentgg.dev | User | Người thuê thứ hai |
| restricted@rentgg.dev | **RESTRICTED** | Thử bị chặn đặt đơn (`USER_RESTRICTED`) |
| suspended@rentgg.dev | **SUSPENDED** | Thử bị chặn đăng nhập hoặc giao dịch |

### Game

| Code | ToS | Ghi chú |
|---|---|---|
| demo-game | ALLOWED | Tham số mặc định §14.1: thuê 60–1440 phút, giữ cọc 72h, giữ doanh thu 72h/168h |
| quick-game | ALLOWED | **Chỉ dùng cho dev.** Thuê tối thiểu 5 phút, giữ cọc 1h, không giữ doanh thu, cửa sổ khiếu nại 30 phút, hủy miễn phí 0 phút |
| review-game | REVIEW_REQUIRED | Game mới tạo, chưa thẩm định, không cho tạo listing hay đặt đơn |
| banned-game | PROHIBITED | Có một yêu cầu GAME_ALLOW đang chờ duyệt |

### Game account và listing

| Account | Chủ | Game | Trạng thái account | Listing |
|---|---|---|---|---|
| demo_player_01 | owner | demo | AVAILABLE | ACTIVE, auto-accept, 30k/h, cọc 200k |
| demo_player_02 | owner | demo | AVAILABLE | ACTIVE, **chủ duyệt đơn**, 45k/h, cọc 300k |
| demo_player_03 | owner2 | demo | AVAILABLE | ACTIVE, GRACE_FOR_CURRENT_MATCH, 80k/h |
| demo_player_04 | owner2 | demo | AVAILABLE | **PAUSED**, STAY_ONLINE_UNTIL_RECLAIM |
| demo_player_05 | owner | demo | AVAILABLE | **PENDING_REVIEW** (vào hàng chờ duyệt của admin) |
| demo_player_06 | owner | demo | AVAILABLE | **REJECTED**, có ghi chú lý do |
| demo_player_07 | owner2 | demo | **PENDING_VERIFICATION** (chưa xác minh khôi phục) | **DRAFT** |
| demo_player_08 | owner2 | demo | **SUSPENDED_SECURITY_REVIEW** (bị gắn cờ, chủ đã đổi mật khẩu) | không có |
| quick_player_01 | owner | quick | AVAILABLE | ACTIVE, 60k/h, cọc 50k |
| quick_player_02 | owner2 | quick | AVAILABLE | ACTIVE, 60k/h, cọc 50k |

Mật khẩu game của mỗi account là `demo-password-<username>`. Riêng demo_player_08 đã đổi thành `demo-password-08-new`.

### Đơn thuê

Giờ bắt đầu tính theo thời điểm seed, theo giờ Việt Nam.

| Người thuê | Listing | Thời gian | Trạng thái lúc seed | Diễn biến sau đó |
|---|---|---|---|---|
| renter | demo_player_01 | Ngày mai 20:00, 120 phút | **CONFIRMED** (đã trả) | Tự chuyển IN_RENTAL lúc 20:00 |
| renter2 | demo_player_01 | Ngày kia 20:00, 60 phút | **PENDING_PAYMENT** | Sau 15 phút không trả thì thành **EXPIRED** |
| renter | demo_player_02 | Ngày mai 20:00, 60 phút | **AWAITING_OWNER_APPROVAL** | owner duyệt hoặc từ chối trong 30 phút. Quá hạn thì thành REJECTED (APPROVAL_TIMEOUT) |
| renter2 | demo_player_02 | Ngày kia 20:00, 60 phút | **REJECTED** (owner từ chối) | Hoàn 100% |
| renter2 | demo_player_01 | +90 phút, 60 phút | **CANCELLED** (hủy muộn, RENTER_LATE_CANCELLED) | Hoàn 50% tiền thuê và 100% cọc. 50% còn lại vào SELLER_REVIEW_HOLD của owner |
| renter | demo_player_01 | Ngày kia 22:00, 60 phút | **CANCELLED** (hủy trước khi trả) | Cọc bị void |
| renter | quick_player_01 | +2 phút, **5 phút** | **CONFIRMED** | Khoảng 2 phút sau: IN_RENTAL. Khoảng 7 phút sau: **ENDED**, doanh thu về AVAILABLE của owner, cọc giữ 1h rồi hoàn |
| renter2 | quick_player_02 | +2 phút, **30 phút** | **CONFIRMED** | Khoảng 2 phút sau: IN_RENTAL kéo dài 30 phút, để thử xem credentials, khiếu nại, takeover |

### Dual approval đang chờ

| Loại | Người tạo | Người duyệt được | Kết quả khi duyệt |
|---|---|---|---|
| GAME_ALLOW (banned-game) | admin | admin2 (không phải người tạo) | banned-game chuyển sang ALLOWED |
| ACCOUNT_SECURITY_RESTORE (demo_player_08) | support | admin hoặc admin2 | Account về AVAILABLE |
| SELLER_HOLD_EARLY_RELEASE (đơn hủy muộn) | support | finance hoặc admin | Doanh thu đang giữ của owner chuyển sang AVAILABLE |

### Dữ liệu phát sinh tự động

- **Ledger:** bút toán PAYMENT_CAPTURED, ORDER_CANCELLED_REFUND, ORDER_CANCELLED_REVENUE. Xem tại `GET /api/v1/admin/ledger/reconciliation`.
- **Refund:** các khoản hoàn của đơn bị từ chối hoặc bị hủy. FAKE PSP hoàn thành công ngay sau khi job outbox chạy.
- **Audit log** cho mọi hành động admin, support và chủ account.
- **Notification:** sinh từ outbox (job chạy mỗi phút). Đăng nhập renter hoặc owner rồi xem `GET /api/v1/notifications`.

## 2. Dữ liệu chưa seed được, và luồng để tạo

Các dữ liệu dưới đây phụ thuộc vào thời gian trôi qua với clock thật, hoặc cần đơn đã kết thúc. Seeder không thể tạo chúng ngay lúc khởi động mà không chèn SQL thẳng (sẽ làm sai ledger và vướng trigger append-only ở V7).

Job nền chạy mỗi phút. Muốn chạy ngay thì đăng nhập admin rồi gọi `POST /api/v1/dev/jobs/run-all`.

### 2.1 Đơn IN_RENTAL và ENDED

Đến khoảng phút thứ 2 sau khi khởi động là có sẵn, nhờ 2 đơn quick-game. Muốn tạo thêm:

1. renter: `POST /api/v1/orders` với `listingId` là listing quick-game, `startAt` = bây giờ + 1 phút, `durationMinutes: 5`, kèm header `Idempotency-Key`.
2. renter: `POST /api/v1/orders/{id}/pay`, rồi `POST /api/v1/dev/payments/{intentId}/simulate-success`. Đơn chuyển sang CONFIRMED.
3. Đợi đến `startAt`. Job `startDueOrders` chuyển đơn sang **IN_RENTAL**.
4. renter: `GET /api/v1/orders/{id}/credentials`, rồi `POST /api/v1/orders/{id}/handover/confirm`.
5. Đợi hết giờ thuê. Job `endDueOrders` chuyển đơn sang **ENDED**, account chuyển sang ROTATION_PENDING.
6. owner: `POST /api/v1/game-accounts/{accountId}/rotation` để đổi mật khẩu. Account về AVAILABLE.

### 2.2 Dispute (khiếu nại)

Cần đơn ở đúng trạng thái. Dùng đơn quick-game 30 phút của renter2, hoặc đơn 5 phút sau khi đã ENDED.

| Loại | Ai mở | Điều kiện |
|---|---|---|
| RENTER_LOGIN_FAILED, RENTER_NOT_AS_DESCRIBED | Người thuê | Đơn đang IN_RENTAL và còn trong cửa sổ khiếu nại (quick-game: 30 phút) |
| ACCOUNT_TAKEOVER | Chủ account | Đơn IN_RENTAL (kết thúc đơn ngay, người thuê bị RESTRICTED) hoặc ENDED |
| OWNER_DAMAGE | Chủ account | Đơn ENDED và cọc còn đang giữ (quick-game: 1h) |

Luồng:
1. `POST /api/v1/orders/{orderId}/disputes` với body `{ "type", "description", "evidenceUrls": [] }`.
2. Hai bên bổ sung bằng chứng: `POST /api/v1/disputes/{id}/evidence`.
3. support: `GET /api/v1/support/disputes`, rồi `POST /api/v1/support/disputes/{id}/resolve` với `decision: RESOLVE|REJECT`, số tiền trừ cọc hoặc hoàn tiền thuê.

### 2.3 Rút tiền (withdrawal)

Cần số dư AVAILABLE. Owner có số dư này sau khi đơn quick-game 5 phút ENDED (khoảng 8 phút sau khởi động, quick-game không giữ doanh thu). Một cách khác là finance duyệt yêu cầu SELLER_HOLD_EARLY_RELEASE đã seed.

1. owner: `GET /api/v1/wallet/balances` để xem `available`.
2. owner: `POST /api/v1/wallet/withdrawals` với `amount`, `bankName`, `bankAccountNumber`, `bankAccountName`, kèm `Idempotency-Key`.
3. finance: `GET /api/v1/finance/withdrawals`, rồi `.../{id}/mark-paid` (`bankRef`) hoặc `.../{id}/mark-failed` (`reason`).

### 2.4 Hoàn hoặc trừ cọc sau thuê

Sau khi đơn ENDED, cọc ở trạng thái giữ (quick-game 1h, demo-game 72h). Hết hạn mà không có OWNER_DAMAGE thì job `releaseDeposits` hoàn cọc. Nếu có dispute thì cọc chờ support quyết định.

### 2.5 Account quá hạn đổi mật khẩu (ROTATION_OVERDUE)

Sau khi đơn ENDED, nếu owner không đổi mật khẩu trong 60 phút (`rentgg.rotation.sla-minutes`) thì job `markRotationOverdue` chuyển account sang ROTATION_OVERDUE.

### 2.6 Thanh toán thất bại hoặc refund lỗi

- Thanh toán thất bại: gửi webhook `POST /api/v1/payments/webhook/FAKE` có chữ ký HMAC (`rentgg.payment.webhook-secret`), body `{"providerRef":"fake_<intentId>","status":"FAILED","amount":...}`.
- Refund lỗi: FAKE PSP chỉ bật lỗi được trong test (`FakePaymentGateway.setFailRefunds`). Ở dev chưa có endpoint để bật.

### 2.7 LEDGER_ADJUSTMENT

Không seed vì đây là điều chỉnh tiền thủ công, cần số liệu thật. Luồng: finance gọi `POST /api/v1/admin/approvals` với `actionType: LEDGER_ADJUSTMENT` và payload `postings`, rồi một finance hoặc admin khác duyệt.

### 2.8 Không còn áp dụng

- **KYC:** không còn bắt buộc, nên không seed. Endpoint `POST /api/v1/me/kyc` vẫn còn.
- **Refresh token:** được tạo khi đăng nhập (`POST /api/v1/auth/login`).
- **Abuse signals:** phát sinh tự nhiên, ví dụ khi chủ account hủy đơn muộn hoặc account không sẵn sàng lúc đơn bắt đầu.
