# RentGG — Checklist kiểm chứng trước khi ra mắt (Bước 1)

**Phiên bản:** 1.0 · **Ngày:** 2026-10-26
**Mục đích:** những việc chỉ đội RentGG tự làm được (thử với account thật, gọi PSP, phỏng vấn người dùng). Kết quả của checklist này được nhập vào cấu hình `Game` trong backend (xem System Design v1.2 §5.2), nên mỗi câu hỏi bên dưới đều tương ứng với một trường dữ liệu.

---

## A. Checklist theo từng game (làm cho 1–3 game ra mắt)

Thực hiện trên **account thử nghiệm của chính đội**, không dùng account của người khác.

| # | Câu hỏi | Cách kiểm tra | Trường trong `Game` | Kết quả |
|---|---|---|---|---|
| A1 | ToS / Community Guidelines có cấm chia sẻ, cho thuê, thương mại hóa account không? Ghi link + đoạn trích + ngày đọc | Đọc ToS bản VN và bản gốc | `tos_status`, `tos_url`, `tos_reviewed_at` | |
| A2 | Đăng nhập trên thiết bị mới có hỏi OTP / email xác minh / app xác thực không? | Đăng nhập trên máy và mạng khác | `handover_mode` = `STATIC_CREDENTIAL` hoặc `OWNER_ASSISTED_OTP` | |
| A3 | Đổi mật khẩu có tự đá các phiên đang đăng nhập ra không? Bao lâu thì bị đá? | Đăng nhập ở máy A, đổi mật khẩu ở máy B, quan sát máy A (cả lúc đang trong trận) | `password_change_kicks_sessions` | |
| A4 | Có chức năng "đăng xuất khỏi mọi thiết bị" không? | Tìm trong cài đặt bảo mật | ghi chú vận hành | |
| A5 | Người đang đăng nhập có thể đổi mật khẩu, email, SĐT khôi phục mà **không cần** OTP gửi về email/SĐT của chủ không? | Thử đổi từ phiên đăng nhập bằng mật khẩu | Nếu **có** → không đạt yêu cầu bảo vệ chủ account → `REVIEW_REQUIRED` | |
| A6 | Có giới hạn số thiết bị hoặc khóa khi đăng nhập từ nhiều IP/quốc gia không? | Tài liệu hỗ trợ + thử | `risk_notes` | |
| A7 | Có tài sản tiêu hao / chuyển nhượng được không (vật phẩm, tiền trong game, rank)? | Quan sát | dùng để gợi ý mức cọc `deposit_min` / `deposit_max` | |
| A8 | Một trận trung bình kéo dài bao lâu? | Quan sát | `grace_minutes` (mặc định 10) | |
| A9 | Độ tuổi tối thiểu theo nhà phát hành | ToS | `min_age` | |
| A10 | Thời lượng thuê hợp lý (tối thiểu/tối đa) | Phỏng vấn | `min_duration_minutes`, `max_duration_minutes` | |

**Quy tắc quyết định:**
- A1 cấm rõ → `PROHIBITED`. Không rõ → `REVIEW_REQUIRED`. Hệ thống không nhận đơn cho cả hai trạng thái này.
- A5 = "có" → game không đủ điều kiện bảo vệ chủ account → không mở.
- A2 = "có OTP" → `OWNER_ASSISTED_OTP`: chủ phải online lúc bàn giao, **không bật `AUTO_ACCEPT` tức thời** cho game đó.
- A3 = "không đá phiên" → `FORCE_LOGOUT_AT_END` không có tác dụng thật; chỉ cho phép chế độ `GRACE` kèm cảnh báo rõ cho chủ account.

---

## B. Câu hỏi cho PSP (VNPay / MoMo / PayOS / ZaloPay…)

1. Có hỗ trợ **marketplace / chia tiền (split settlement)** hoặc tài khoản thu hộ cho nhiều người bán không?
2. Tiền thu từ người thuê nằm ở đâu trong thời gian giữ (72h sau thuê + thời gian tranh chấp)? Ai đứng tên?
3. **Hoàn tiền cọc**: API hoàn tiền có giới hạn thời gian không (ví dụ chỉ trong 30 ngày)? Hoàn một phần có được không? Mất bao lâu đến người thuê?
4. **Chi trả cho chủ account**: có API payout / chi hộ không? Phí? Hạn mức? Yêu cầu KYC người nhận?
5. Webhook: cơ chế chữ ký (HMAC?), retry, idempotency, IP whitelist.
6. Chargeback / khiếu nại giao dịch: quy trình, thời hạn, ai chịu.
7. Môi trường sandbox và thời gian tích hợp.
8. Yêu cầu giấy phép / hồ sơ pháp lý từ phía RentGG để dùng mô hình thu hộ – chi hộ.

> Câu trả lời cho câu 1–4 quyết định ledger có phản ánh "tiền thật nằm ở PSP" hay không. Backend MVP đã tách cổng thanh toán qua interface `PaymentGateway`, nên đổi PSP không ảnh hưởng domain.

---

## C. Kịch bản phỏng vấn (10–20 người, 20 phút/người)

**Chủ account**
1. Bạn đang cho thuê ở đâu (group, shop, nền tảng nào)? Bao nhiêu account? Thu nhập trung bình mỗi tháng?
2. Bạn bàn giao account như thế nào? Có đổi mật khẩu sau mỗi lần thuê không? Mất bao lâu?
3. Sự cố tệ nhất từng gặp (bị chiếm account, phá rank, mất đồ)? Xử lý ra sao?
4. Bạn sẽ đặt mức cọc bao nhiêu cho account của mình? Cọc giữ bao lâu sau thuê thì bạn yên tâm?
5. Nếu nền tảng bắt bạn đổi mật khẩu trước khi nhận đơn tiếp theo, bạn có chấp nhận không? Bạn phản hồi được trong bao lâu?
6. Bạn chấp nhận nhận tiền sau bao lâu (72h? 7 ngày?) đổi lại được bảo vệ?

**Người thuê**
1. Bạn thuê game gì, bao lâu một lần, mỗi lần bao nhiêu tiền?
2. Mức cọc tối đa bạn chấp nhận so với tiền thuê? Chờ hoàn cọc tối đa bao lâu?
3. Nếu đang bị giữ cọc đơn trước mà muốn thuê account khác, bạn có sẵn sàng đặt thêm cọc mới không?
4. Bạn từng bị lừa khi thuê (mật khẩu sai, account khác mô tả) chưa?

**Chỉ số cần rút ra:** mức cọc chấp nhận được (theo % tiền thuê), thời gian giữ cọc chấp nhận được, thời gian phản hồi đổi mật khẩu của chủ, tỷ lệ người thuê bỏ đi vì phải đặt cọc lần hai.

---

## D. Đầu ra của Bước 1

- [ ] Bảng A đã điền cho từng game ra mắt → nhập vào `POST /api/v1/admin/games`
- [ ] Đã chọn PSP + biết mô hình thu hộ / chi hộ
- [ ] Mức cọc min/max và thời gian giữ cọc theo game (thay giá trị mặc định 72h)
- [ ] Danh sách 5–10 chủ account đồng ý tham gia beta kín
