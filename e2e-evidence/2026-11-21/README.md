# Bằng chứng E2E trên trình duyệt thật (2026-11-21)

- Commit app được kiểm: `e439d1e` (dev sau merge PR #29 và PR #3).
- Script: `frontend/e2e/web-evidence.cjs` trên nhánh `claude/e2e-web-evidence` (PR #30).
- `e2e-run3/`, `e2e-run4/`: hai lần chạy liên tiếp, đều 3/3 đạt. Mỗi thư mục có ảnh PNG, `video/*.webm`, `results.json`.
- `e2e-negative2/`: negative control (kỳ vọng sai ở F3), đỏ đúng chỗ, exit 1.
- `f2-429-frames/`: khung hình từ video F2 lần chạy thứ hai, cho thấy thông báo "Bạn thao tác quá nhiều lần" (429 RATE_LIMITED) sau khi đăng nhập `renter@` nhiều lần trong một phút.
- Lần chạy 1 và 2 không lưu ở đây: lần 1 không có lỗi; lần 2 là lần 429 nêu trên, chỉ lưu khung hình.

## Lần chạy hợp lệ (2026-11-21, script ở `376e590` trên `claude/e2e-web-evidence`)

Mỗi lần chạy trên DB `rentgg_e2e` mới (drop/create + khởi động lại backend), trừ khi ghi khác.

| Thư mục | Kết quả | Exit | Ghi chú |
| --- | --- | --- | --- |
| `e2e-run8/` | 5/5 | 0 | DB mới, F2 xác minh nhãn "Đã xác nhận" |
| `e2e-run9/` | 5/5 | 0 | DB mới lần hai, để kiểm tra lặp lại |
| `e2e-negative4/` | 3/5 | 1 | Âm tính có chủ đích: F2 và F3 đỏ đúng chỗ |
| `e2e-run6/` | 5/5 | — | Chạy trên DB đã dùng một lần; chỉ tham khảo |
| `e2e-negative3/` | — | — | **Đã thay thế** bởi `e2e-negative4/` (DB đã dùng, F4 đỏ sai nguyên nhân) |

Chưa phủ (không tự động hóa trong lần này): kiểm thử mobile/simulator (không có máy được gắn vào phiên). Thanh toán của người thuê, tua thời gian (🕒) và admin từ chối bài đăng đã phủ ở lần chạy 11, xem bên dưới.

## Lần chạy 10 (DB mới, script `frontend/e2e/web-evidence.cjs` sau khi sửa tour)

- `e2e-run10/`: 5/5, exit 0. Sửa: tour được chờ tối đa 3 giây và được bỏ qua sau mỗi lần `goto` (F2 danh sách, F3 admin). Ảnh `f2-renter-orders.png` và `f3-admin-listings.png` đã kiểm tra là sạch; ảnh `*-tour-shown.png` là ảnh trước khi bỏ qua.
- Lần chạy 8 và 9 được chụp trước khi sửa tour: kết quả chữ vẫn đúng nhưng ảnh F2 danh sách và F3 có thể bị tour che. Nếu cần ảnh sạch, dùng `e2e-run10/`.

## Lần chạy 11 và âm tính 6 (DB mới, script `685c0ca` trên `claude/e2e-web-evidence`)

Đã thêm F5 (thanh toán ví), F7 (admin từ chối bài đăng, chủ thấy lý do) và F6 (tua 🕒 +1 ngày, rồi về giờ thật). Mỗi lần chạy trên DB `rentgg_e2e` mới, backend dev khởi động lại.

| Thư mục | Kết quả | Exit | Ghi chú |
| --- | --- | --- | --- |
| `e2e-run11/` | 8/8 | 0 | Lần sạch. F6 ghi nhận giờ đổi từ "00:54 22/11/26" sang "00:54 23/11/26 · +23 giờ 59 phút", rồi về giờ thật |
| `e2e-negative6/` | 3/8 | 1 | Âm tính `--expect-fail`. Đỏ đúng chỗ: F2 xác minh, F3 tiêu đề, F5 kỳ vọng "Chờ thanh toán" còn, F7 lý do khác, F6 kỳ vọng sai có chủ đích |

Lưu ý:
- `e2e-negative5/` không được dùng làm bằng chứng. Lần đó bị dừng giữa chừng, nên F5–F7 báo `Target page … has been closed`, không phải do kỳ vọng sai. Thư mục không được đưa vào nhánh này.
- F6 trong lần âm tính luôn đỏ có chủ đích, và để đồng hồ dev lệch. Lần chạy kế tiếp xoá `backend/target/dev-clock-*.properties` trước khi chạy.
- Đã xem bằng mắt các ảnh F5–F7 của `e2e-run11/`:
  - `f5-renter-order-paid.png`: đơn "Đang thuê", đã thanh toán, không còn "Chờ thanh toán".
  - `f5-renter-wallet-tour-shown.png`: tour chào mừng hiện lúc vào ví; script bỏ qua tour trước khi thao tác (ảnh này là trước khi bỏ qua).
  - `f6-time-shifted.png`: panel tua thời gian mở, đồng hồ góc dưới "00:54 23/11/26 · +23 giờ 59 phút".
  - `f7-admin-rejected.png`: admin hiện "Đã từ chối listing." không bị tour che.
  - `f7-admin-tour-shown.png`: tour hiện trước khi bỏ qua (ảnh trước khi bỏ qua).
  - `f7-owner-sees-reason.png`: **không hiển thị lý do từ chối trên màn hình** (chỉ thấy đầu trang "Account cho thuê"). Việc chủ thấy lý do chỉ được kiểm bằng assert chữ `Lý do từ chối: <ghi chú>` trong `results.json`, chưa có bằng chứng ảnh. Cần chụp lại vùng listing đã từ chối nếu muốn có ảnh.
- Ảnh và video nằm trong từng thư mục run.
