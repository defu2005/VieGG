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

Chưa phủ (không tự động hóa trong lần này): thanh toán của người thuê, tua thời gian (🕒), admin từ chối bài đăng, kiểm thử mobile/simulator (không có máy được gắn vào phiên).

## Lần chạy 10 (DB mới, script `frontend/e2e/web-evidence.cjs` sau khi sửa tour)

- `e2e-run10/`: 5/5, exit 0. Sửa: tour được chờ tối đa 3 giây và được bỏ qua sau mỗi lần `goto` (F2 danh sách, F3 admin). Ảnh `f2-renter-orders.png` và `f3-admin-listings.png` đã kiểm tra là sạch; ảnh `*-tour-shown.png` là ảnh trước khi bỏ qua.
- Lần chạy 8 và 9 được chụp trước khi sửa tour: kết quả chữ vẫn đúng nhưng ảnh F2 danh sách và F3 có thể bị tour che. Nếu cần ảnh sạch, dùng `e2e-run10/`.
