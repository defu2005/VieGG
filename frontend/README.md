# RentGG Frontend

Giao diện web cho RentGG, viết bằng **React 18 + TypeScript + Vite**, React Router. Là một ứng dụng độc lập, cài đặt và chạy riêng với backend (`../`).

## Yêu cầu
- Node.js 20 trở lên
- Backend đang chạy (mặc định `http://localhost:8080`, xem README gốc)

## Cài đặt và chạy

```bash
cd frontend
npm install
npm run dev
```

Mở http://localhost:5173. Dev server proxy mọi request `/api/*` tới backend, nên trình duyệt không gọi cross-origin và không cần cấu hình CORS.

Nếu backend chạy ở cổng khác, tạo `frontend/.env.local` (xem `.env.example`):

```bash
VITE_API_TARGET=http://localhost:8081
```

## Build và kiểm tra kiểu

```bash
npm run typecheck   # tsc --noEmit
npm run build       # tsc -b && vite build, ra thư mục dist/
npm run preview     # xem bản build
```

Bản build là file tĩnh trong `dist/`. Khi triển khai thật, phục vụ `dist/` cùng domain với API (hoặc reverse proxy `/api` tới backend) để tránh CORS.

## Cấu trúc

```
src/
  api/        client.ts (fetch + Bearer token + refresh một lần khi 401), types.ts (DTO khớp backend)
  auth/       AuthContext.tsx (phiên đăng nhập, vai trò)
  components/ Layout, RouteGuards, StatusBadge, ListingCard, OrderStepper, Countdown, DisputesSection
  lib/        format.ts (tiền VND, ngày giờ), attributes.ts (thẻ thông tin listing), useFlash.tsx (báo lỗi/thành công)
  pages/      Trang theo chức năng (xem bảng dưới)
```

### Trang người dùng

| Route | Trang | Ghi chú |
|---|---|---|
| `/login`, `/register` | Đăng nhập, đăng ký | Đăng ký yêu cầu đủ 18 tuổi |
| `/profile` | Hồ sơ | Thông tin tài khoản, nộp KYC (tùy chọn). Bấm tên hiển thị trên thanh trên cùng |
| `/listings` | Tìm account | Lọc game, giá, khung giờ trống |
| `/listings/:id` | Chi tiết và đặt thuê | Lịch bận, quy định của game |
| `/orders` | Đơn của tôi | Tab "Tôi thuê" / "Tôi cho thuê" |
| `/orders/:id` | Chi tiết đơn | Thanh toán, duyệt, bàn giao, credentials, OTP, hủy, khiếu nại |
| `/my-listings` | Account cho thuê | Thêm account, đổi mật khẩu, lấy lại, tạo và sửa listing |
| `/wallet` | Ví | Số dư, rút tiền, lịch sử rút, cọc |
| `/notifications` | Thông báo | Đánh dấu đã đọc |

### Trang quản trị (admin, support, finance)

Không có trang chủ riêng cho quản trị (không có route `/admin`). Sau khi đăng nhập bằng tài khoản staff, thanh trên cùng hiện thêm một hàng menu **Quản trị** bên dưới menu thường. Menu và route chỉ hiện với đúng vai trò; quyền thật do backend kiểm tra, vào sai quyền sẽ thấy "Bạn không có quyền truy cập trang này".

| Route | Menu | Vai trò được vào | Chức năng |
|---|---|---|---|
| `/admin/listings` | Kiểm duyệt | ADMIN, SUPPORT | Lọc listing theo trạng thái. Duyệt, từ chối, đình chỉ (chỉ ADMIN). Xác minh khôi phục và gắn cờ account nghi ngờ |
| `/support/disputes` | Khiếu nại | ADMIN, SUPPORT | Danh sách khiếu nại, phân xử: khấu trừ cọc, hoàn tiền thuê, đổi trạng thái người thuê |
| `/admin/games` | Game | ADMIN, SUPPORT | Xem game. ADMIN: tạo game, sửa chính sách, đổi trạng thái ToS |
| `/admin/users?id=<uuid>` | Người dùng | ADMIN, SUPPORT | Tra cứu theo ID: đổi trạng thái, mức rủi ro, tín hiệu lạm dụng. Duyệt KYC (chỉ ADMIN) |
| `/admin/approvals` | Phê duyệt | ADMIN, SUPPORT, FINANCE | Phê duyệt kép: duyệt, từ chối, tạo yêu cầu mới |
| `/finance` | Tài chính | ADMIN, FINANCE | Danh sách rút tiền (chỉ FINANCE đánh dấu đã chi hoặc thất bại), đối soát sổ cái, bút toán theo đơn |

Liên kết sang quản trị từ trang khác: trang chi tiết đơn (với ADMIN, SUPPORT) có link tới người thuê và chủ account; màn Kiểm duyệt có link "Xem chủ account".

#### Tài khoản staff khi chạy backend profile `dev`

`DevDataSeeder` tạo sẵn các tài khoản (đuôi `@rentgg.dev`): `admin@`, `admin2@`, `support@`, `finance@`, cùng `owner@` và `renter@`. Mật khẩu là `DEV_PASSWORD` trong `backend/src/main/java/com/rentgg/jobs/DevDataSeeder.java`. Phê duyệt kép cần hai người khác nhau, ví dụ tạo yêu cầu bằng `admin@` rồi duyệt bằng `admin2@`.

Tình trạng chi tiết và các tính năng còn thiếu: `../docs/RentGG_Frontend_Status.md`.

## Lưu ý

- Token lưu trong `localStorage` (khóa `rentgg.tokens`). Phiên hết hạn thì tự refresh; refresh thất bại thì quay về màn đăng nhập.
- Nút "Giả lập thanh toán (dev)" chỉ hiện khi chạy `npm run dev`, gọi endpoint dev của backend.
- Header `Idempotency-Key` được tạo mới cho mỗi thao tác ghi tiền (đặt đơn, thanh toán, rút tiền).
