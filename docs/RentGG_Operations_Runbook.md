# RentGG — Runbook vận hành

Tài liệu này ghi những gì đã đọc trong mã nguồn và cấu hình, kèm đường dẫn. Mục nào chưa chạy thật trong môi trường thật được ghi **chưa kiểm chứng**.

Dựng production từ đầu (VPS, DNS, Cloudflare, R2): xem `docs/RentGG_Production_Deploy.md`. Không lặp lại ở đây.

## 1. Khởi động và điều kiện từ chối khởi động

Nguồn: `backend/src/main/java/com/rentgg/config/StartupSafetyCheck.java` (chạy trong `@PostConstruct`). Khi một điều kiện không đạt, ứng dụng không khởi động. Thông báo lỗi nêu tên biến, không in giá trị.

Mọi profile:

| Điều kiện | Hậu quả |
|---|---|
| Profile `dev` đi cùng `prod` hoặc `staging` | Từ chối khởi động |
| `RENTGG_JWT_SECRET` trống, hoặc Base64 giải mã dưới 32 byte | Từ chối khởi động |
| `RENTGG_PAYMENT_WEBHOOK_SECRET` dưới 32 ký tự | Từ chối khởi động |

Chỉ profile `staging` và `prod`:

| Điều kiện | Ghi chú |
|---|---|
| Khóa `devkey` làm key đang dùng, hoặc webhook secret/JWT là giá trị mặc định của dev | Từ chối |
| `RENTGG_STAFF_MFA_REQUIRED` không phải `true` | Từ chối |
| Secret chứa `changeme`, `account_id` hoặc `example`, hoặc chỉ lặp một ký tự | Từ chối. Chuỗi `xxx` không nằm trong danh sách này, có chủ đích (xem comment trong file) |
| `RENTGG_PAYMENT_FAKE_ENABLED` là `true` trên `prod` | Từ chối |
| `RENTGG_PUBLIC_BASE_URL`, `RENTGG_FRONTEND_BASE_URL` không bắt đầu bằng `https://` | Từ chối |
| `RENTGG_CORS_ALLOWED_ORIGINS` trống | Từ chối |
| `RENTGG_PAYMENT_PROVIDER` trống, là `FAKE`, hoặc không thuộc `PAYOS`, `VNPAY`, `MOMO`, `ZALOPAY` | Từ chối |
| Biến của provider đã chọn trống hoặc chứa placeholder | Từ chối. Danh sách biến theo provider nằm trong `providerCredentials()` cùng file |
| `rentgg.media.storage` là `s3` mà thiếu một trong 4 biến `RENTGG_S3_ENDPOINT`, `RENTGG_S3_BUCKET`, `RENTGG_S3_ACCESS_KEY`, `RENTGG_S3_SECRET_KEY` | Từ chối |

Ngoài các kiểm tra trên, `RENTGG_CREDENTIAL_KEYS` được kiểm tra placeholder khi chạy staging/prod (`requireRealSecret`). Mục này nằm trong cùng file.

Bảng biến môi trường đầy đủ hơn nằm ở `docs/RentGG_System_Design_v1.2.md` §14.3. Tài liệu đó nhắc biến webhook theo từng provider (`RENTGG_PAYMENT_WEBHOOK_SECRET_<PROVIDER>`). **Chưa kiểm chứng** trong code: không tìm thấy kiểm tra cho tên biến này khi đọc `StartupSafetyCheck`.

## 2. Job định kỳ

Nguồn: `backend/src/main/java/com/rentgg/jobs/JobScheduler.java` (13 `@Scheduled`, mỗi job có `@SchedulerLock`), `backend/src/main/java/com/rentgg/jobs/Jobs.java`.

- Chu kỳ: `rentgg.jobs.cron`, mặc định `0 * * * * *` (`backend/src/main/resources/application.yml` dòng 97).
- Mỗi job dùng ShedLock với `lockAtMostFor` `PT5M` (`publishOutbox`: `PT2M`).
- Job nhận `Clock` của ứng dụng, nên dev có thể tua thời gian (xem `CLAUDE.md`).
- Lỗi trong job: `Jobs.run()` bắt `RuntimeException`, ghi log `Job {name} failed`, tăng `rentgg_job_failures_total`, và trả về 0. Lỗi không dừng scheduler.

Danh sách 13 job (tên trùng với method trong `Jobs.java`):

| Job | Làm gì (theo code) |
|---|---|
| `expirePendingPayments` | Hết hạn đơn chờ thanh toán |
| `expireExtensionPayments` | Hết hạn thanh toán gia hạn |
| `expireOwnerApprovals` | Đơn quá hạn owner duyệt: `REJECTED`, hoàn 100% |
| `startDueOrders` | Đến giờ bắt đầu: `IN_RENTAL` hoặc hủy nếu account chưa sẵn sàng |
| `endDueOrders` | Hết giờ thuê: `ENDED`, cọc sang `POST_RENTAL_HOLD` |
| `sendEndingReminders` | Nhắc sắp hết giờ |
| `releaseSellerHolds` | Giải phóng tiền giữ của người bán khi đến hạn |
| `releaseDeposits` | Hoàn cọc `POST_RENTAL_HOLD` quá `hold_until` |
| `markRotationOverdue` | Đánh dấu owner quá hạn đổi mật khẩu |
| `markDisputeDeadlinesOverdue` | Đánh dấu khiếu nại quá hạn xử lý |
| `markLegalNoticeDeadlines` | Đánh dấu hạn thông báo pháp lý |
| `retryFailedRefunds` | Thử lại hoàn tiền `FAILED` có backoff |
| `publishOutbox` | Phát sự kiện outbox chưa phát |

Lệch so với tài liệu: `docs/RentGG_System_Design_v1.2.md` §9 chỉ liệt kê 9 job. Bốn job `expireExtensionPayments`, `markDisputeDeadlinesOverdue`, `markLegalNoticeDeadlines`, `retryFailedRefunds` có trong code nhưng không có trong bảng §9. Khi có mâu thuẫn, lấy `Jobs.java` và `JobScheduler.java`.

Metric (`Jobs.java`): `rentgg_job_runs_total`, `rentgg_job_records_total`, `rentgg_job_failures_total`, `rentgg_job_duration_seconds`, đều có tag `job`.

Chạy tay (chỉ profile `dev`): `POST /api/v1/dev/jobs/run-all`, nguồn `backend/src/main/java/com/rentgg/jobs/DevController.java` (có `@Profile("dev")`).

Cảnh báo Prometheus: `deploy/monitoring/rentgg-alerts.yml` có `RentggOutboxStuck`, `RentggOutboxBacklog`, `RentggRefundRetryFailing`. **Chưa kiểm chứng** cảnh báo nào đã được nối tới kênh thông báo nào.

## 3. Việc cần kiểm tra khi có triệu chứng

Các mục dưới đây chỉ nêu cách kiểm tra theo mã. Không có quy trình xử lý sự cố được viết sẵn trong repo.

**Outbox không phát.** Bảng `outbox_events`: `published_at IS NULL`, cột `attempts`, `last_error`. Job `publishOutbox` thử lại với backoff (`docs/RentGG_System_Design_v1.2.md` §10.1). Alert: `RentggOutboxStuck`, `RentggOutboxBacklog`.

**Hoàn tiền thất bại.** Bảng `refunds` có trạng thái `FAILED` và cột `attempts`. Job `retryFailedRefunds` gọi `PaymentService.retryFailedRefunds`. Thời gian chờ giữa các lần thử là `min(3600, 60 × 2^min(attempts, 6))` giây (`backend/src/main/java/com/rentgg/payment/application/PaymentService.java`, dòng 404). Số lần thử tối đa là `MAX_REFUND_ATTEMPTS`. Alert: `RentggRefundRetryFailing`.

**Hoàn tiền đi đâu.** `PaymentService.processRefund` đánh dấu refund `SUCCEEDED` với phương thức `wallet`. Bút toán ghi `REFUND_PAYABLE` → `SELLER_AVAILABLE` của người trả tiền. Tiền hoàn vào số dư ví RentGG của người thuê. Code không gọi API hoàn tiền của PSP. **Chưa kiểm chứng**: có quy trình nào khác chuyển tiền ra ngoài hay không.

**Rút tiền của chủ account.** `WalletService.requestWithdrawal` ghi `WITHDRAWAL_REQUESTED`. Nhân viên tài chính đánh dấu `markPaid` (kèm `bankRef`) hoặc `markFailed`, mỗi lần ghi một bút toán (`backend/src/main/java/com/rentgg/wallet/application/WalletService.java`). Trong `backend/src/main/java/com/rentgg/payment` không có hàm chi tiền qua PSP (không tìm thấy `payout` ở đây). Việc chuyển khoản ngân hàng thực tế diễn ra ngoài hệ thống. **Chưa kiểm chứng** quy trình đó trên thực tế.

**Webhook thanh toán.** `POST /api/v1/payments/webhook/{provider}` cho phép không cần đăng nhập (`backend/src/main/java/com/rentgg/shared/security/SecurityConfig.java`, dòng 66). Việc xác thực chữ ký nằm trong `PaymentGateway.verifyCallback`. Đầu vào sai định dạng ném `IllegalArgumentException`. Các gateway thật nằm trong `backend/src/main/java/com/rentgg/payment/infrastructure/psp/` (`PayosGateway`, `VnpayGateway`, `MomoGateway`, `ZalopayGateway`). **Chưa kiểm chứng** với PSP thật: chưa có key sandbox (xem `docs/RentGG_Validation_Checklist.md`).

**Khiếu nại và thông báo pháp lý quá hạn.** Hai job `markDisputeDeadlinesOverdue` và `markLegalNoticeDeadlines` chỉ đánh dấu, không tự quyết định. Quyết định là việc của nhân viên hỗ trợ.

## 4. Sao lưu và khôi phục

Nguồn: `deploy/backup/backup.sh`, `deploy/restore.sh`.

- `backup.sh`: chạy mỗi ngày lúc `BACKUP_HOUR` (mặc định 3), múi giờ `BACKUP_TZ` (mặc định `Asia/Ho_Chi_Minh`). Ghi `pg_dump -Fc --no-owner` lên R2 tại `db/rentgg-YYYYMMDD-HHMMSS.dump`. Xóa bản cũ hơn `BACKUP_KEEP_DAYS` (mặc định 30).
- Sao lưu ngay: `docker compose ... exec backup backup.sh now`.
- `restore.sh` không có tham số: liệt kê bản sao lưu.
- `restore.sh <file>`: yêu cầu gõ `YES`, rồi dừng app, khôi phục trong một transaction, và luôn bật lại app (trap). Khôi phục **ghi đè** toàn bộ DB hiện tại.
- Sau khi khôi phục, script nhắc chạy đối soát ledger trước khi mở lại giao dịch: `GET /api/v1/admin/ledger/reconciliation` (`backend/src/main/java/com/rentgg/ledger/api/LedgerAdminController.java`, dòng 26).
- Quyền khôi phục: `restore.sh` dùng `--role=rentgg_migrator` (`docs/RentGG_Production_Deploy.md`, mục B1a).

Đã kiểm thử: `docs/RentGG_Production_Deploy.md` mục B3 ghi kiểm thử backup và restore trên máy dev với S3Mock, gồm cả trường hợp file sai. **Chưa kiểm chứng** trên server thật và R2 thật.

## 5. Quản trị viên đầu tiên và xác thực hai bước

- Cấp admin đầu tiên: `deploy/make-admin.sh <email>`. Tài khoản phải đã đăng ký trước. Sau đó đăng nhập, bật xác thực hai bước theo hướng dẫn trong trang quản trị.
- Mất thiết bị xác thực: quy trình SQL thủ công trong `docs/RentGG_Production_Deploy.md` mục B1b. Lưu ý trong tài liệu đó: access token là JWT, thu hồi refresh token không hủy access token đang dùng, cho tới khi hết hạn (tối đa 15 phút). **Chưa kiểm chứng** quy trình này trên server thật.

## 6. Chưa có trong repo

- Lịch trực, kênh báo sự cố, SLA, quy trình leo thang: không có tài liệu nào trong repo.
- Kiểm chứng restore trên server thật, R2 thật, Let's Encrypt thật, Cloudflare proxy thật: chưa làm (`docs/RentGG_Production_Deploy.md`, mục B3).
- Kiểm chứng PSP thật và chuyển tiền thật: chưa làm (xem mục 3).
