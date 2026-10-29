# RentGG — Tình trạng Frontend

**Ngày:** 2026-10-26 · **Rà soát:** 2026-10-29
**Mã nguồn:** `frontend/` (React 18, TypeScript, Vite, React Router)
**Đối chiếu với:** `docs/RentGG_Implementation_Status.md` và API backend trong `backend/`

---

## 1. Đã triển khai

| Nhóm | Màn hình (route) | Chức năng |
|---|---|---|
| Tài khoản | `/login`, `/register`, `/profile` | Đăng nhập, đăng ký (đủ 18 tuổi), tự refresh token, xem hồ sơ, nộp KYC (tùy chọn, backend không còn bắt buộc) |
| Người thuê | `/listings`, `/listings/:id` | Tìm theo game, giá, khung giờ trống; xem quy định, lịch bận; đặt đơn |
| Đơn hàng | `/orders`, `/orders/:id` | Thanh toán, hủy, duyệt hoặc từ chối đơn (chủ account), xác nhận nhận account, xem credentials, yêu cầu và gửi OTP |
| Khiếu nại | `/orders/:id` (mục Khiếu nại) | Mở khiếu nại theo vai trò, thêm bằng chứng (link https), xem kết quả phân xử |
| Chủ account | `/my-listings` | Thêm account game, đổi mật khẩu (rotation), lấy lại account, tạo, sửa, gửi duyệt, tạm dừng listing |
| Ví | `/wallet` | Số dư, rút tiền, lịch sử rút, danh sách cọc theo đơn |
| Thông báo | `/notifications` | Danh sách, lọc chưa đọc, đánh dấu đã đọc |
| Kiểm duyệt (ADMIN, SUPPORT) | `/admin/listings` | Duyệt, từ chối, đình chỉ listing; xác minh khôi phục; gắn cờ account nghi ngờ; xem chủ account |
| Khiếu nại (ADMIN, SUPPORT) | `/support/disputes` | Lọc theo trạng thái và loại, phân xử (khấu trừ cọc, hoàn tiền, đổi trạng thái người thuê) |
| Game (ADMIN, SUPPORT) | `/admin/games` | Tạo game, sửa chính sách, đổi trạng thái ToS (mở lại game cần phê duyệt kép) |
| Người dùng (ADMIN, SUPPORT) | `/admin/users?id=` | Tra cứu theo ID, đổi trạng thái, mức rủi ro, duyệt KYC, xem tín hiệu lạm dụng |
| Phê duyệt kép | `/admin/approvals` | Danh sách theo trạng thái, duyệt, từ chối, tạo yêu cầu (khôi phục account, nhả doanh thu sớm, điều chỉnh sổ cái) |
| Tài chính (FINANCE, ADMIN) | `/finance` | Danh sách rút tiền, đánh dấu đã chi hoặc thất bại, đối soát sổ cái, bút toán theo đơn |
| Hỗ trợ | `/help`, `/tickets`, `/tickets/:id` | Trang trợ giúp và ticket hỗ trợ (đặt theo tên route; chức năng chi tiết chưa kiểm tra) |
| Quản trị hệ thống (ADMIN) | `/admin/roles`, `/admin/settings` | Quản lý vai trò và cấu hình ứng dụng (chưa kiểm tra chi tiết) |

Phân quyền ở giao diện chỉ để ẩn và chặn điều hướng. Quyền thật do backend kiểm tra.

**Route quản trị:** `/admin/listings`, `/support/disputes`, `/admin/games`, `/admin/users`, `/admin/approvals`, `/finance`, `/admin/roles`, `/admin/settings`. Không có route `/admin` làm trang chủ; menu "Quản trị" là hàng thứ hai của thanh trên cùng, chỉ hiện với tài khoản staff. Bảng vai trò và tài khoản dev: `frontend/README.md`.

## 2. Đã kiểm thử trên trình duyệt (backend thật, profile dev)

- Người thuê: đăng nhập, tìm kiếm, đặt đơn, thanh toán giả lập, xem hồ sơ, ví, chặn truy cập trang quản trị.
- Chủ account: đổi mật khẩu, thêm account, sửa listing (listing đang hiển thị quay về chờ duyệt).
- ADMIN: duyệt listing, sửa chính sách game, đổi ToS và đổi lại, tra cứu người dùng, tạo yêu cầu phê duyệt (báo lỗi đúng khi sai ID).
- FINANCE: trang tài chính, chạy đối soát (sổ cái cân bằng).
- `tsc --noEmit` và `vite build` đều thành công.


## 2b. Cải thiện giao diện (kiểu sàn thuê account)

Lưu ý về nguồn tham khảo: trang zuhaowan.com là ứng dụng JavaScript và không hiển thị được trong trình duyệt tích hợp, còn tìm kiếm web chỉ cho thông tin bên ngoài. Vì vậy các cải tiến dưới đây dựa trên mẫu giao diện phổ biến của nhóm sàn thuê account, **không phải sao chép từ trang cụ thể nào**.

| Khu vực | Cải tiến |
|---|---|
| Trang chủ | Banner, lưới game, danh sách account giá tốt, hướng dẫn 3 bước, thanh bảo đảm (mã hóa mật khẩu, cọc riêng từng đơn, đổi mật khẩu sau mỗi lượt, khiếu nại), lời mời đăng account cho thuê |
| Tìm account | Chip chọn game, chip khoảng giá, lọc khung giờ trống thu gọn, bộ lọc nằm trên URL (chia sẻ được), thẻ listing có nhãn game, thẻ thông tin nổi bật, nhãn "Nhận đơn ngay", khung chờ khi tải |
| Chi tiết listing | Chọn nhanh giờ bắt đầu và thời lượng, tổng tiền cập nhật tức thì (thuê + cọc), cảnh báo trùng lịch hoặc giờ đã qua và khóa nút đặt, giải thích cách duyệt đơn |
| Đơn hàng | Thanh tiến trình 5 bước, đếm ngược hạn thanh toán và hạn duyệt, lọc theo trạng thái, nút "Thuê lại" |
| Chủ account | Nhập "thông tin nổi bật" (rank, server, số tướng, trang phục...) dạng tên: giá trị, hiện thành thẻ cho người thuê |
| Độ bền | Phiên hết hạn không còn làm trống trang công khai: khi refresh token thất bại, request được thử lại không kèm token |

## 3. Chưa kiểm thử (đã code nhưng chưa chạy trên dữ liệu thật)

- Mở khiếu nại và thêm bằng chứng: cần đơn ở trạng thái `IN_RENTAL` hoặc `ENDED`, chưa tạo được trong môi trường dev vì phụ thuộc giờ thuê thật.
- Phân xử khiếu nại của support, đánh dấu rút tiền đã chi hoặc thất bại, duyệt phê duyệt kép bởi người thứ hai, đổi trạng thái hoặc mức rủi ro người dùng, duyệt KYC.
- Luồng OTP (game `OWNER_ASSISTED_OTP`), rút tiền, lấy lại account (`reclaim`).
- Giao diện điện thoại mới xem trang chi tiết listing (không tràn ngang, bố cục một cột); các trang còn lại và chế độ sáng chưa xem thực tế.

## 4. Còn thiếu

### 4.1. Thiếu vì backend chưa có API

| # | Hạng mục | Chi tiết |
|---|---|---|
| 1 | Tìm kiếm và danh sách người dùng | Chỉ có `GET /admin/users/{id}`. Giao diện phải tra cứu bằng ID, đi từ liên kết trong đơn hoặc listing |
| 2 | Danh sách account chờ xác minh khôi phục, danh sách KYC chờ duyệt | Backend chưa có endpoint liệt kê. Hiện xác minh khôi phục được từ màn kiểm duyệt listing |
| 3 | Tra cứu nhật ký audit | Backend chưa có endpoint |
| 4 | Danh sách đơn cho support và admin | Chỉ xem được đơn khi biết ID. Backend `GET /orders` chỉ trả đơn của chính người gọi |
| 5 | Xem account game của người khác | `GET /game-accounts/{id}` chỉ cho chủ sở hữu |
| 6 | Ghi chú rủi ro của game (`riskNotes`) | `GET /games` không trả về trường này, nên sửa chính sách sẽ ghi đè bằng giá trị trống nếu không nhập lại |
| 7 | Tải bằng chứng lên | Backend chỉ nhận link `https://`, chưa có upload (đã ghi trong tài liệu backend) |
| 8 | Thanh toán thật | Backend đã có adapter PSP (MoMo, PayOS, VNPay, ZaloPay). Giao diện chỉ có nút "Giả lập thanh toán" khi chạy `npm run dev`. Cần kiểm tra luồng thanh toán thật trên sandbox |
| 9 | Kháng nghị quyết định kiểm duyệt và phân xử (UC-05, UC-13) | Backend chưa có |
| 10 | Hồ sơ thông báo pháp lý từ nhà phát hành (UC-20) | Backend chưa có |
| 11 | MFA cho staff, xác thực lại khi xem credentials (D5) | Backend chưa làm |
| 12 | Tính năng Phase 1 trở đi | Chat, coupon, shop và tier, gia hạn đơn, chấm điểm uy tín, ảnh listing (UC-35) |

### 4.2. Thiếu ở phía frontend

| # | Hạng mục | Ghi chú |
|---|---|---|
| 1 | Test tự động (unit, e2e) và cấu hình lint | Chưa có |
| 2 | Chuông thông báo và số chưa đọc trên thanh trên cùng, cập nhật thời gian thực | Hiện phải vào trang Thông báo |
| 3 | Liên kết từ thông báo sang đơn hoặc dispute liên quan | Dữ liệu `data` của thông báo chưa được dùng |
| 4 | Phân trang trên danh sách đơn, thông báo, khiếu nại, rút tiền, cọc | Hiện lấy tối đa 50 mục mỗi danh sách |
| 5 | Triển khai production | `frontend` chưa có trong `docker-compose.yml`. Cần phục vụ `dist/` cùng domain với API hoặc đặt `RENTGG_CORS_ALLOWED_ORIGINS` |
| 6 | Token lưu trong `localStorage` | Dễ bị lấy nếu có XSS. Nên chuyển sang cookie `HttpOnly` khi backend hỗ trợ |
| 7 | Đa ngôn ngữ | Chỉ có tiếng Việt, chuỗi viết trực tiếp trong code |
| 8 | Hình ảnh, sắp xếp, lọc theo rank hoặc server | Backend sắp theo giá tăng dần, chưa có ảnh listing, tham số sắp xếp hay lọc theo thuộc tính. Thẻ thông tin nổi bật chỉ để hiển thị |
| 8b | Đánh giá và điểm uy tín của chủ account, số lượt thuê | Backend chưa có đánh giá. Đây là phần các sàn Trung Quốc nhấn mạnh nhất |
| 8c | Đăng nhập một chạm ("上号器") | Cần phần mềm khách và game cho phép. Chưa có trong backend và chưa rõ về điều khoản của nhà phát hành |
| 9 | Hỗ trợ trợ năng (a11y) | Chưa kiểm tra bằng trình đọc màn hình |
| 10 | ~~Form khiếu nại không khớp backend~~ **Đã sửa (2026-10-29)** | `DisputesSection.tsx` giờ chỉ hiện lựa chọn khiếu nại của người thuê khi đơn `IN_RENTAL` và chưa xác nhận bàn giao; chủ account chỉ báo thiệt hại khi `ENDED`; "chiếm account" mở được ở cả hai trạng thái. Hạn từng rủi ro (cửa sổ khiếu nại) vẫn do backend kiểm. `tsc` và `vite build` qua; **chưa thử trên trình duyệt** vì cần đơn đang `IN_RENTAL` (có thể tạo bằng công cụ tua thời gian dev) |
