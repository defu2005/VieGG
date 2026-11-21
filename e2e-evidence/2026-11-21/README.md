# Bằng chứng E2E trên trình duyệt thật (2026-11-21)

- Commit app được kiểm: `e439d1e` (dev sau merge PR #29 và PR #3).
- Script: `frontend/e2e/web-evidence.cjs` trên nhánh `claude/e2e-web-evidence` (PR #30).
- `e2e-run3/`, `e2e-run4/`: hai lần chạy liên tiếp, đều 3/3 đạt. Mỗi thư mục có ảnh PNG, `video/*.webm`, `results.json`.
- `e2e-negative2/`: negative control (kỳ vọng sai ở F3), đỏ đúng chỗ, exit 1.
- `f2-429-frames/`: khung hình từ video F2 lần chạy thứ hai, cho thấy thông báo "Bạn thao tác quá nhiều lần" (429 RATE_LIMITED) sau khi đăng nhập `renter@` nhiều lần trong một phút.
- Lần chạy 1 và 2 không lưu ở đây: lần 1 không có lỗi; lần 2 là lần 429 nêu trên, chỉ lưu khung hình.
