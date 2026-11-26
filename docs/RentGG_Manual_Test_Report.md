# RentGG — Báo cáo kiểm thử thủ công

Báo cáo này lấy từ ma trận kiểm thử (`results.json`, bảng 106 hàng cố định) và nhật ký phát hiện. Mỗi kết quả là một lần chạy thật trên backend dev chạy trong container, trình duyệt tự động. Đây không phải kiểm thử trên production, không có thiết bị thật, không có PSP thật.

Bằng chứng (video `.webm`, ảnh chụp) lưu ngoài repo, không commit vào repo. Nếu cần xem, hỏi người chạy lại kịch bản.

## 1. Tổng kết

| Trạng thái | Số hàng |
|---|---|
| PASS | 96 |
| ĐÃ THAY THẾ | 5 |
| BLOCKED | 4 |
| NOT_IMPLEMENTED | 1 |
| **Tổng** | **106** |

- `ĐÃ THAY THẾ`: hàng FAIL hoặc BLOCKED gốc, đã được thay bằng hàng `-fix` ghi kết quả sau khi sửa. Hàng gốc giữ nguyên để có lịch sử.
- `BLOCKED`: không chạy được vì thiếu thứ container không có (khóa PSP thật, bucket R2/S3 thật, simulator iOS/Android) hoặc vì bị chặn bởi bộ phân loại quyền (xem mục 4).
- `NOT_IMPLEMENTED`: tính năng chưa có trong backend.
- Quy tắc PASS: chỉ ghi PASS khi có video đủ lớn và ít nhất một ảnh trên đĩa. Kiểm tra tự động trong quá trình viết báo cáo: không có hàng PASS nào thiếu bằng chứng.

## 2. Kết quả theo hàng

| ID | Chức năng | Trạng thái | Chi tiết | Ảnh |
|---|---|---|---|---|
| A01 | Đăng nhập sai mật khẩu báo lỗi | PASS | báo lỗi "Email hoặc mật khẩu không đúng.", ở lại /login / lỗi kỹ thuật: console: Failed to load resource: the server responded with a status of 401 (Unauthorized) | 1 ảnh |
| A02 | Đăng nhập đúng theo từng vai (cũng làm mới token) | PASS | renter thấy Đơn của tôi; owner thấy Account cho thuê; admin thấy Kiểm duyệt, Phân quyền; support thấy Hỗ trợ; finance thấy Tài chính / 403 tại: /api/v1/games / lỗi kỹ thuật: console: Failed to load resource: the server responded with a status of 403 (Forbidden); console: Failed to load resource: the | 5 ảnh |
| A03 | Người thuê không vào được trang quản trị | PASS | URL cuối /admin/listings; tiêu đề ""; không có menu Kiểm duyệt | 1 ảnh |
| A04 | Admin từ chối bài đăng có lý do (Từ chối), chủ thấy lý do | PASS | admin Từ chối → POST reject 2xx; API REJECTED; chủ thấy "Lý do từ chối: Ảnh bìa chưa rõ 1795315772264" | 3 ảnh |
| A05 | Admin duyệt bài đăng đang chờ | PASS | chủ gửi duyệt → PENDING_REVIEW; admin bấm Duyệt → POST approve 2xx; API ACTIVE | 2 ảnh |
| A06 | Xác minh khôi phục account | PASS | API: account 1b60cfe7 PENDING_VERIFICATION -> AVAILABLE (đọc từ /admin/game-accounts?state=AVAILABLE); không còn trong hàng PENDING_VERIFICATION (1 -> 0); UI đã bấm nút Xác minh / chạy lại (không dùng cho bằng chứng gốc): verify-recovery 200 trên account 060e2ca1; đọc lại API: securityState=AVAILABL | 2 ảnh |
| A07 | Gắn cờ account nghi ngờ | PASS | thông báo "Đã gắn cờ account nghi ngờ." hiện (lý do SUSPECTED_FRAUD qua hộp thoại). Audit ACCOUNT_FLAGGED_SUSPICIOUS 00:29:28Z trên account 7a0eee4a, lý do SUSPECTED_FRAUD. Trạng thái cuối: cờ đã bị gỡ — yêu cầu khôi phục d6865785 (OWNER_VERIFIED) do admin tạo lúc 01:09:32Z, admin2 duyệt, thực thi 0 | 2 ảnh |
| A08 | Tra cứu người dùng theo ID | PASS | tra cứu ID 69afa20c-e656-41f0-87d8-f577efffe673 hiển thị renter@rentgg.dev | 1 ảnh |
| A09 | Đổi trạng thái người dùng (có audit) | PASS | API: RESTRICTED -> ACTIVE; đã khôi phục về RESTRICTED | 2 ảnh |
| A10 | Đổi mức rủi ro người dùng | PASS | API đọc mức rủi ro sau khi đổi: HIGH; đã khôi phục STANDARD | 2 ảnh |
| A11 | Quản lý game: tạo, sửa chính sách | PASS | game "e2e-642300" tạo và hiện trong danh sách; sửa phí nền tảng 1200 bps, sau khi tải lại vẫn 1200 / lỗi kỹ thuật: console: Pattern attribute value [a-z0-9_-]{2,40} is not a valid regular expression: Uncaught SyntaxError: Invalid regular expression: /[a-z0-9_-]{2,40}/v: Invalid chara Cải tiến: ô slu | 4 ảnh |
| A12 | Phê duyệt kép: admin tạo yêu cầu | PASS | lần chạy lại (a4c.cjs) dừng ở bước kiểm tra điều kiện trước finishFlow, nên không có requests.json: nó cần account đang bị gắn cờ, mà account 7a0eee4a đã được khôi phục bằng yêu cầu d6865785 lúc 01:09:53Z. Hàng gốc giữ nguyên. | 2 ảnh |
| A13 | Phê duyệt kép: người thứ hai duyệt | PASS | admin@ tạo GAME_ALLOW → admin@ gọi API duyệt chính mình: HTTP 403 (bị chặn); admin2@ bấm "Duyệt" trên thẻ, chấp nhận hộp thoại → POST approve 200, trạng thái EXECUTED Lưu ý: lần chạy này thực thi thật GAME_ALLOW: game 9c080945 được mở lại cho thuê. | 2 ảnh |
| A14 | Vai trò và cấu hình (chỉ đọc) | PASS | (chỉ đọc: không thay đổi dữ liệu) Phân quyền: có mục vai trò, 6 tham chiếu vai trò; Cấu hình: 18 ô nhập/chọn, 1973 ký tự nội dung | 2 ảnh |
| A15 | Thông báo: đánh dấu tất cả đã đọc | PASS | chưa đọc 21 -> 0 | 2 ảnh |
| A16 | Tickets: tạo và trả lời | PASS | người thuê tạo "E2E ticket 1795321659352" (hiện trong danh sách); hỗ trợ mở và trả lời, câu trả lời hiện | 3 ảnh |
| A17 | Tài chính: đối soát sổ cái | PASS | kết quả đối soát hiện "Sổ cái cân bằng"; dấu ✗ duy nhất nằm trong phần hướng dẫn (đọc ảnh); mọi mục ✓. Lưu ý: finance nhận 403 từ GET /api/v1/games (menu/trang tải danh sách game) — cần xem RBAC | 1 ảnh |
| A18 | Chủ: đổi mật khẩu account (rotation) | PASS | API: securityState ROTATION_PENDING -> AVAILABLE; rotationDueAt trống / không có requests.json: xoay mật khẩu account làm đổi credential đang dùng | 2 ảnh |
| A19 | Chủ: thêm account game | PASS | API: số account 5 -> 6; account mới securityState=PENDING_VERIFICATION (xem ảnh; form cần tick 3 xác nhận sở hữu) / chạy lại: số account 7 -> 8; account mới 04c2bacc đọc qua API sau đó (hàng giờ, không phải ngay sau lần chạy): securityState=PENDING_VERIFICATION; bộ đọc của kịch bản trả "?" ở lần chạ | 2 ảnh |
| A20 | Chủ sửa rồi gửi duyệt lại bài bị từ chối | PASS | gửi duyệt lại: thông báo "Chờ duyệt" hiện (chữ này có thể trùng tiêu đề thẻ, không dùng làm bằng chứng trạng thái). Audit listing 4af30b11: LISTING_REJECTED 00:15:51Z (note "Ảnh bìa chưa rõ 1795306479228"), LISTING_TERMS_CHANGED 00:27:14Z, LISTING_APPROVED 00:27:28Z (ACTIVE). Bước gửi lại không có b | 3 ảnh |
| A21 | Nạp ví giả lập (dev) | PASS | API: tổng số dư tăng đúng 500000 ₫ (832000 -> 1332000); thông báo hiện / không có requests.json: a2 chạy chung A20 và A05 nên chưa tách được; chưa chạy lại. | 1 ảnh |
| A22 | Staff MFA: đăng ký và đăng nhập có mã | PASS | Nửa đăng nhập lại (chạy lại bằng backend 8081 với RENTGG_STAFF_MFA_REQUIRED=true, frontend 5284): mật khẩu không kèm mã → POST /auth/login 401 MFA_REQUIRED, UI hiện ô "Mã xác thực hai bước"; nhập TOTP đúng → 200, vào được /admin/approvals. Nửa đăng ký: xem A22-enrolled.png (lần chạy trước). Sau chạy | 4 ảnh |
| A23 | Đăng xuất (phiên hiện tại) | PASS | đăng xuất xong; vào /profile bị chuyển về /login | 2 ảnh |
| A24 | Đăng xuất mọi thiết bị | PASS | hộp thoại xác nhận "Đăng xuất khỏi tất cả thiết bị, kể cả thiết bị này?" được chấp nhận; URL /login; token trong máy đã xoá | 1 ảnh |
| A25 | Đăng ký tài khoản mới | PASS | đăng ký e2e-g1-1795311970506@rentgg.dev; rời /register sang / / không có requests.json: email đã tồn tại, không chạy lại được | 2 ảnh |
| A26 | Hồ sơ: sửa tên hiển thị | PASS | tên hiển thị đổi thành "E2E G1 đổi 1795321718890" và hiện sau khi lưu | 2 ảnh |
| A27 | Hồ sơ: gửi KYC | PASS | form đã ẩn (gửi ở lần chạy trước), xác minh trạng thái; API /me kycStatus=PENDING / không có requests.json: hồ sơ KYC đã nộp, tiêu thụ trạng thái | 1 ảnh |
| A28 | Đổi mật khẩu (/me/password) | PASS | đổi mật khẩu thành công; đăng nhập lại bằng mật khẩu mới ở A23/A24 / không có requests.json: đổi mật khẩu làm đổi mật khẩu test | 2 ảnh |
| A30 | Phân quyền: gán vai trò cho người dùng | PASS | gán 2 trang: POST users/a720f5ec-af3b-4a61-be5a-1427f0420b2f/roles và POST …/users/524ca428-76b1-48c0-bc78-421c77e8f5d6/roles đều 2xx; API xác nhận cả hai | 3 ảnh |
| A31 | Phân quyền: gỡ vai trò khỏi người dùng | PASS | gỡ 2 trang: DELETE renter2 và DELETE G1 đều 2xx; API xác nhận cả hai đã gỡ | 3 ảnh |
| A32 | Vai trò: tạo vai trò mới | PASS | POST /admin/rbac/roles 2xx; API: E2E16618415 staff=false | 2 ảnh |
| A33 | Vai trò: sửa quyền của vai trò | PASS | PUT permissions 2xx; API khớp ô đã chọn (2 quyền); 11 ô bị khoá (không có quyền để cấp) | 2 ảnh |
| A34 | Vai trò: bật/tắt cờ "Vai trò nhân viên" (có xác nhận) | PASS | PUT /admin/rbac/roles/E2E16618415 2xx x2; API staff true → false; 2 hộp xác nhận đều được script chấp nhận | 2 ảnh |
| A35 | Vai trò: xoá vai trò | PASS | DELETE /admin/rbac/roles/E2E16618415 2xx; API không còn vai trò | 1 ảnh |
| A36 | Admin: tạm dừng (suspend) listing | PASS | POST /admin/listings/462768e2/suspend 2xx (reasonCode MISLEADING_LISTING); API SUSPENDED | 2 ảnh |
| A38 | Cấu hình hệ thống: lưu giá trị | PASS | PUT /admin/settings 2xx; banner.dismissible: true → false → khôi phục true | 2 ảnh |
| A39 | Game: sửa rủi ro và gói | PASS | thêm bộ gợi ý (6 rủi ro) → PUT risks 2xx, API 6; xóa hết → lưu, API 0 (khôi phục ban đầu) | 3 ảnh |
| A40 | Game: đổi trạng thái điều khoản (TOS) | PASS | ALLOWED→SUSPENDED (admin@ UI, POST tos-status 200, API SUSPENDED); đặt lại ALLOWED → 202 yêu cầu duyệt kép 7c0b2e7f; admin2@ duyệt UI → POST approve 2xx, EXECUTED; API ALLOWED. Hộp thoại: script,script,script | 5 ảnh |
| A41 | Đơn: người thuê hủy đơn | PASS | đặt: CONFIRMED; POST /orders/9a721fb2/cancel 2xx → CANCELLED; ví 185000 → 60000 (đặt) → 185000 (hủy) | 2 ảnh |
| A42 | Đơn: chủ từ chối đơn | PASS | xoay vòng account qua owner API: tài khoản đã AVAILABLE khi chạy (điều kiện ROTATION_OVERDUE không được kiểm tra lại trước khi đặt); renter đặt → AWAITING_OWNER_APPROVAL; chủ bấm "Từ chối" (confirm) → POST reject 2xx, đơn REJECTED; hoàn tiền bất đồng bộ (PENDING rồi SUCCEEDED trong vài giây); số dư | 3 ảnh |
| A43 | Đơn: người thuê xác nhận nhận account (handover) | ĐÃ THAY THẾ bởi A43-fix (FAIL gốc → A43-fix) | ĐÃ THAY THẾ bởi A43-fix (PASS). người thuê không xác nhận được bàn giao qua giao diện. Bằng chứng: (1) mã: nút chỉ hiện khi CONFIRMED (OrderDetailPage.tsx:151, 308) nhưng backend chỉ nhận khi IN_RENTAL (HandoverService.java:159-160); (2) ảnh A43-in-rental.png: đơn Đang thuê, không có nút; (3) API PO | 3 ảnh |
| A45 | Gia hạn thuê: tạo yêu cầu gia hạn | PASS | số dư khả dụng 10000 < 22.500; gia hạn +30 phút → UI giữ chỗ (PENDING_PAYMENT), hiện "Thanh toán bằng ví" và "Hủy yêu cầu" / lỗi kỹ thuật: console: Failed to load resource: the server responded with a status of 422 (Unprocessable Entity) | 2 ảnh |
| A46 | Gia hạn thuê: thanh toán gia hạn | PASS | nạp 300.000 (intent 39c7f9a3) thay vì 100.000 như dự kiến: script bấm nút "Giả lập" đầu tiên trong danh sách, và mỗi yêu cầu nạp chưa thanh toán có nút riêng (947397fc vẫn CREATED); "Thanh toán bằng ví" → POST pay 2xx; gia hạn đã áp dụng: CANCELLED,PAID. POST /orders/…/extensions 422 là lần tạo gia | 2 ảnh |
| A47 | Gia hạn thuê: huỷ gia hạn | PASS | bấm "Hủy yêu cầu" → POST cancel 2xx; gia hạn chuyển CANCELLED, không còn PENDING_PAYMENT | 2 ảnh |
| A48 | Lịch bận: thêm khoảng bận | PASS | POST /listings/{id}/busy-blocks 2xx; API có khoảng bận ghi chú "E2E bận 1795315240063" / lỗi kỹ thuật: console: Failed to load resource: the server responded with a status of 409 (Conflict) 409 là bước kiểm tra trùng có chủ đích: khoảng 2 giờ tới đè lên đơn đang thuê → UI hiện "trùng một đơn thuê đã | 3 ảnh |
| A49 | Lịch bận: xoá khoảng bận | PASS | DELETE busy-blocks 2xx; API không còn ghi chú "E2E bận 1795315240063" | 2 ảnh |
| A50 | Ảnh listing: tải ảnh lên | PASS | POST ảnh 201; API: số ảnh 1 → 2 | 2 ảnh |
| A51 | Ảnh listing: xoá ảnh | PASS | DELETE ảnh 2xx; API: số ảnh 2 → 1 | 1 ảnh |
| A52 | Hỗ trợ từ đơn: tạo ticket hỗ trợ | PASS | link "Chat với hỗ trợ về đơn này" → /tickets?new=1&orderId=; POST /tickets 201; ticket f3c587b4 | 2 ảnh |
| A55 | Ticket: đánh dấu đã đọc | PASS | POST /tickets/38ddd9f7/read 2xx khi mở; unread 1 → 0 (API) | 1 ảnh |
| A56 | Ticket: đổi trạng thái | PASS | POST /tickets/bcdeb77d/status 2xx; API RESOLVED | 1 ảnh |
| A57 | Ticket: thêm người tham gia | PASS | POST /support/tickets/bcdeb77d/participants 2xx; API có thêm người tham gia | 2 ảnh |
| A58 | Ticket: gỡ người tham gia | PASS | DELETE /support/tickets/…/participants/{financeId} 2xx (có xác nhận); API: finance đã gỡ, người thuê còn | 1 ảnh |
| A59 | Ticket: phân công người xử lý | PASS | POST …/assign 2xx; API assigneeId = support | 1 ảnh |
| A60 | Hỗ trợ: tạo ticket từ trang hỗ trợ | PASS | POST /tickets 201; ticket bcdeb77d hiện trong danh sách | 2 ảnh |
| A61 | Thông báo: đánh dấu tất cả đã đọc | PASS | POST /notifications/read-all 2xx; nút đánh dấu tất cả biến mất | 2 ảnh |
| A67 | Chủ: đặt khoá tài khoản (account-key) | PASS | POST /game-accounts 2xx; PUT account-key 200; UI báo "Đã lưu … cho account." | 4 ảnh |
| A68 | Chủ: gợi ý mật khẩu mới | PASS | POST password-suggestion 200; chưa lưu mật khẩu nào | 1 ảnh |
| A69 | Chủ: tạo listing (có ảnh) | PASS | ảnh đã thêm (API có ảnh), POST /listings 2xx, gửi duyệt → PENDING_REVIEW, admin Duyệt → ACTIVE; listing 462768e2 | 3 ảnh |
| A70 | Chủ: tạm dừng listing (/pause) | PASS | POST pause 2xx; API PAUSED; khôi phục: submit 200, approve 200 | 2 ảnh |
| A71 | Tài chính: đánh dấu rút thất bại | PASS | chủ tạo 2f1aa5ab 30.000 (REQUESTED, payoutProcessing +30.000); finance bấm "Thất bại" + lý do; POST mark-failed 2xx; API FAILED; ví hoàn available 155200→185200, payoutProcessing về 0 / ghi chú console: 403 ×2 là GET /api/v1/games?all=true khi finance@ vừa vào /admin/approvals; ApprovalsPage.tsx:37 | 2 ảnh |
| A72 | Dev: chạy tất cả job | PASS | bấm "Chạy job ngay" → POST /dev/jobs/run-all 2xx | 1 ảnh |
| A73 | Thông báo: đánh dấu một thông báo đã đọc | PASS | POST /notifications/{id}/read 2xx | 2 ảnh |
| A74 | Form phân xử: quyết định từng mục (ACCEPT/REJECT) | PASS | Người thuê (đơn 7cb0676c, IN_RENTAL) chọn 2 rủi ro → khiếu nại RENTER_RISK tạo. Hỗ trợ trên UI: mục 1 "Chấp nhận rủi ro này" (EVIDENCE_CONFIRMED) → POST decide 200, ACCEPTED; mục 2 "Bác" (INSUFFICIENT_EVIDENCE) → POST decide 200, REJECTED; khiếu nại RESOLVED. Lưu ý: lần chạy đầu ghi FAIL do lỗi kiểm | 2 ảnh |
| A75 | Admin: duyệt KYC người dùng | PASS | POST /admin/users/…/kyc {VERIFY} 2xx; API kycStatus VERIFIED (người thử: e2e-kyc-ok-1…) | 2 ảnh |
| A76 | Admin: từ chối KYC người dùng | PASS | POST /admin/users/…/kyc {REJECT, note} 2xx; API kycStatus REJECTED | 2 ảnh |
| A78 | Duyệt kép: từ chối yêu cầu | PASS | admin@ tạo 891b8a98 (GAME_ALLOW); admin2@ từ chối trên UI có ghi chú; POST reject 2xx; API REJECTED, không executedAt; 4 yêu cầu chờ khác vẫn PENDING; hộp thoại: script | 2 ảnh |
| A79 | Dev: đặt giờ cụ thể | PASS | panel "Đặt giờ" (mặc định +60 phút, tiến) → POST /dev/time/set 2xx; độ lệch 0s -> 3591s | 2 ảnh |
| A80 | Dev: đóng băng đồng hồ | PASS | bấm "Đóng băng" → POST freeze 2xx; API frozen=true, now không đổi sau 3 giây; "Cho chạy tiếp" → frozen=false | 1 ảnh |
| A81 | Hồ sơ: đăng xuất khỏi tất cả thiết bị | PASS | Mở rộng A24 (nhánh Hủy, thu hồi phía máy chủ, hộp thoại): đối chứng: refresh phiên đăng nhập thô trước khi đăng xuất → 200; Hủy: không gọi API, vẫn ở /profile; đồng ý: POST /me/logout-all 204, về /login; refresh token đã xoay trước đó → 401 "Refresh token đã bị thu hồi.". Lưu ý: lần kiểm tra này dùn | 2 ảnh |
| B01 | Đặt đơn và thanh toán bằng ví | PASS | đơn e4e6c5a5-fe36-4483-92fb-1c0b32109b53 không còn "Chờ thanh toán"; nhãn trạng thái hiện thấy: có / không có requests.json: tạo đơn thuê mới, cần đồng hồ tua 72h | 2 ảnh |
| B02 | Chủ duyệt đơn chờ duyệt | PASS | đơn ae5f45db từ AWAITING_OWNER_APPROVAL -> CONFIRMED (API xác minh); thông báo "Đã duyệt đơn." hiện (lần đọc trạng thái đầu bị sai trường, đã sửa script và đọc lại bằng danh sách đơn) / không có requests.json: tiêu thụ đơn AWAITING_OWNER_APPROVAL | 2 ảnh |
| B03 | Xem thông tin đăng nhập khi đơn đang thuê | PASS | hiện 2 trường bí mật; tên đăng nhập "demo_player_01" / không có requests.json: chuỗi đơn thuê, cần đồng hồ tua | 2 ảnh |
| B04 | OTP | PASS | trạng thái đơn trước: IN_RENTAL; người thuê bấm "Yêu cầu mã OTP" (hiện "Đã gửi yêu cầu OTP."); chủ nhập mã và bấm "Gửi mã"; người thuê thấy đúng mã OTP700869 trong thông tin đăng nhập. Video chủ: evidence/B04-owner.webm / Khôi phục: quick-game về STATIC_CREDENTIAL sau bài test. / không có requests.j | 5 ảnh |
| B05 | Mở khiếu nại kèm ảnh, video và link https | PASS | khiếu nại hiện với mô tả và bằng chứng (ảnh, video, link https); API xác minh trạng thái khiếu nại OPEN (nhãn đã sửa: không phải nhãn đơn "Đang thuê") / không có requests.json: tạo khiếu nại mới trên đơn đã có | 3 ảnh |
| B05b | Thêm bằng chứng vào khiếu nại đang mở | PASS | thông báo "Đã thêm bằng chứng." hiện; ghi chú "Bổ sung 1795306734112" đã gửi. API (admin): khiếu nại 69e83dbc status=RESOLVED, evidence=4 mục; chữ "Bổ sung" khớp, token 1795306734112 xuất hiện 2 lần trong payload GET /disputes/{id}: mô tả khiếu nại và evidence[].note (đã kiểm bằng cách đếm mọi lần x | 2 ảnh |
| B06 | Hỗ trợ chấp nhận khiếu nại, hoàn tiền thuê | PASS | API: trạng thái RESOLVED, hoàn tiền thuê 1000 ₫, khấu trừ cọc 0 ₫ / không có requests.json: chuỗi khiếu nại, tiêu thụ trạng thái đơn | 3 ảnh |
| B06a | Form phân xử: khấu trừ cọc với khiếu nại người thuê | ĐÃ THAY THẾ bởi B06a-fix3 (FAIL gốc → B06a-fix3) | ĐÃ THAY THẾ bởi B06a-fix3 (PASS, fix 5 trên dữ liệu thật có đối chứng). form cho nhập khấu trừ cọc (ô hiện); backend trả lỗi hiện trên UI; API: trạng thái OPEN — lỗi UX: form không ẩn trường khấu trừ cho khiếu nại người thuê / lỗi kỹ thuật: console: Failed to load resource: the server responded with | 2 ảnh |
| B06r | Hỗ trợ bác khiếu nại (đường REJECT) | PASS | API: trạng thái REJECTED, khấu trừ cọc 0 ₫, hoàn tiền thuê 0 ₫ / không có requests.json: chuỗi khiếu nại, tiêu thụ trạng thái đơn | 2 ảnh |
| B07 | Nhả cọc sau kết thúc | PASS | Video/ảnh cho thấy trạng thái sau khi nhả (ví + đơn e4e6c5a5). API: cọc 200000 ₫ POST_RENTAL_HOLD -> REFUNDED (refundedAmount 200000); before/after ví và cọc nằm trong b07-snapshot.json. Chuyển trạng thái thực hiện bằng tua 72h qua API (/dev/time/advance), không qua UI. / không có requests.json: nhả | 2 ảnh |
| B08 | Rút tiền | PASS | UI gửi yêu cầu 20000 ₫; API: available 145200 -> 125200 (-20000), payoutProcessing 0 -> 20000 (+20000) | 3 ảnh |
| B09 | Đánh dấu rút đã chi | PASS | UI bấm "Đã chi"; API: yêu cầu 092fcf82 REQUESTED -> PAID; payoutProcessing 20000 -> 0 / lỗi kỹ thuật: console: Failed to load resource: the server responded with a status of 403 (Forbidden); console: Failed to load resource: the server responded with a status of 403 (Forbidden) / ghi chú console: 40 | 2 ảnh |
| B10 | Lấy lại account | PASS | UI bấm "Lấy lại account" (xác nhận hộp thoại); API: account d0fca4a1 AWAITING_RECLAIM -> ROTATION_PENDING | 2 ảnh |
| B11 | Tua thời gian dev | PASS | UI "+1 ngày": offset 273599 -> 359999 (+86400s); "Về giờ thật": offset -> 0 / không có requests.json: tua đồng hồ dev thay đổi toàn hệ thống | 3 ảnh |
| C01 | Cổng thanh toán thật | BLOCKED | Không có khóa PSP thật trong container; chỉ dùng giả lập dev. | 0 ảnh |
| C02 | Ảnh/video lên R2 | BLOCKED | BLOCKED: không có bucket R2/khóa S3 thật. Bản cục bộ (S3 tương thích) không chạy được: lệnh cài công cụ bị từ chối quyền, không thử cách khác. Cấu hình RENTGG_MEDIA_STORAGE=s3 có sẵn. | 0 ảnh |
| C03 | Email thông báo | NOT_IMPLEMENTED | CHƯA CÓ TÍNH NĂNG: mã backend không có đường gửi email nào (không có dependency SMTP, không có lớp gửi thư, không có luồng quên mật khẩu hay xác minh email). Không phải lỗi do thiếu khóa. Cần xây tính năng này rồi mới kiểm được. | 0 ảnh |
| C04 | App iOS/Android | BLOCKED | Không có emulator/simulator trong container; chỉ kiểm thử web. | 0 ảnh |
| C05 | Mobile 390px | PASS | /listings: scrollWidth 390 / 390 ok; /orders/e4e6c5a5-fe36-4483-92fb-1c0b32109b53: scrollWidth 390 / 390 ok; /wallet: scrollWidth 390 / 390 ok; /my-listings: scrollWidth 390 / 390 ok; /admin/listings: scrollWidth 390 / 390 ok; /tickets: scrollWidth 390 / 390 ok | 6 ảnh |
| A11-fix | Quản lý game (chạy lại: sửa pattern) | PASS | game "e2e-473122" tạo và hiện trong danh sách; sửa phí nền tảng 1200 bps, sau khi tải lại vẫn 1200 | 4 ảnh |
| A13-fix | Phê duyệt kép (chạy lại: người thứ hai duyệt) | PASS | admin@ tạo GAME_ALLOW → admin@ gọi API duyệt chính mình: HTTP 403 (bị chặn); admin2@ bấm "Duyệt" trên thẻ, chấp nhận hộp thoại → POST approve 200, trạng thái EXECUTED | 2 ảnh |
| A17-fix | Tài chính: đối soát sổ cái (chạy lại) | PASS | kết quả đối soát hiện "Sổ cái cân bằng"; dấu ✗: 1 | 1 ảnh |
| B01-fix | Thuê mới: nút nhận account theo trạng thái đơn | ĐÃ THAY THẾ bởi A43-fix (BLOCKED gốc → A43-fix) | ĐÃ THAY THẾ một phần bởi A43-fix (IN_RENTAL, nút hiện, bấm HTTP 200). nút nhận account ẨN khi đơn CONFIRMED (đúng với fix, xác nhận ở 3 lần chạy). Chưa kiểm được IN_RENTAL: đơn đặt sau bị hủy OWNER_FAULT_NOT_READY khi đến giờ, vì các lần chạy trước đã giữ account f997c726 (tryMarkInUse thất bại). Mỗ | 2 ảnh |
| FX2 | Fix 2: finance@ mở duyệt không gọi /games?all=true | PASS | PASS: finance@ (approval:list có, game:read_hidden không) ở /admin/approvals, heading=[Phê duyệt / Yêu cầu phê duyệt], không có /games?all=true (các lời gọi /games: /api/v1/games, /api/v1/games). Đối chứng admin@ trên cùng trang: /games?all=true ×6, nút "Duyệt" ×2. | 1 ảnh |
| FX3 | Fix 3: support@ xem yêu cầu tự tạo | PASS | PASS: support@ là người tạo 2 yêu cầu PENDING (194c4146, 5b1fc48d). Trên trang support@: "Duyệt" ×0, "Từ chối" ×2. Đối chứng admin@: "Duyệt" ×2. | 1 ảnh |
| FX4-finance | Fix: /admin/listings không gọi /games?all=true khi thiếu game:read_hidden (finance@: 0 lần gọi) | PASS | finance@rentgg.dev: /admin/listings, số lời gọi /games?all=true = 0 (mong đợi 0), path=/admin/listings | 1 ảnh |
| FX4-admin-control | Đối chứng: admin@ có quyền vẫn gọi /games?all=true | PASS | admin@rentgg.dev: /admin/listings, số lời gọi /games?all=true = 4 (mong đợi >0), path=/admin/listings | 1 ảnh |
| FX5-stale-note | A20: tin ACTIVE không còn lý do từ chối cũ | PASS | owner@ /my-listings: note cũ còn trên tin không bị từ chối = false; tin REJECTED "Account mô tả sai" có "Lý do từ chối" = true; tổng thẻ = 14 | 1 ảnh |
| FX6 | OTP: CONFIRMED không có nút; IN_RENTAL có luồng đầy đủ sau sửa (FX6-in-rental) | PASS | (1) CONFIRMED, bf38beef, trình duyệt: badge "Đã xác nhận"; người thuê thấy "Xem thông tin đăng nhập" không có nút OTP; chủ không có khung gửi mã (ảnh FX6-renter-confirmed.png, FX6-owner-confirmed.png). (2) IN_RENTAL sau sửa 5e223dd: luồng đầy đủ đạt, chi tiết ở FX6-in-rental (video FX6-in-rental.web | 2 ảnh |
| FX6-in-rental | Luồng OTP đầy đủ sau sửa ở IN_RENTAL (trong cửa sổ thuê, đồng hồ dev tua): yêu cầu → gửi mã → thấy mã | PASS | tua tới 2026-11-26T07:00:00.000Z (HTTP 200); API credentials renter=200; renter: nút=1, otp-request 2xx=true; owner: khung gửi=1, otp-note 2xx=true; renter thấy mã 570579=true / reset đồng hồ HTTP 200 | 3 ảnh |
| FX7 | Gợi ý khung khiếu nại trên trang đơn (bên thuê), đối chứng chủ account | PASS | đơn 8375b797 status=CONFIRMED; renter: nút xem thông tin đăng nhập=1, dòng nhắc=1; đối chứng owner@: dòng nhắc=0 / hủy đơn test HTTP 200 | 2 ảnh |
| MFA1 | MFA: bật TOTP cho finance@, kiểm đăng nhập đòi mã, rồi gỡ | BLOCKED | BLOCKED: bộ phân loại quyền từ chối lệnh bật TOTP trên tài khoản nhân viên (thay đổi trạng thái tài khoản). Không thử cách khác. Cần người dùng cho phép rõ ràng nếu muốn chạy. | 0 ảnh |
| A43-fix | Bàn giao account khi IN_RENTAL (xoay vòng account) | PASS | PASS: đơn a5603efa (listing 5c9a619c, account d0fca4a1 AVAILABLE sau khi xoay vòng qua POST /game-accounts/{id}/rotation). Đơn đã IN_RENTAL khi đặt xong (không quan sát được trạng thái CONFIRMED trong lần chạy này, nên không khẳng định nút ẩn ở CONFIRMED). Nút bàn giao hiện khi IN_RENTAL, bấm → HTTP | 2 ảnh |
| B06a-fix | Khiếu nại bên thuê: form hỗ trợ không có ô khấu trừ | ĐÃ THAY THẾ bởi B06a-fix3 (BLOCKED gốc → B06a-fix3) | ĐÃ THAY THẾ bởi B06a-fix3. Lần chạy này chỉ kiểm được nhánh RENTER_RISK (không chứng minh fix 5). Chỉ kiểm được nhánh RENTER_RISK, không chứng minh fix 5. Đơn e2408e83 (listing 93229f28, renter2, IN_RENTAL, chưa bàn giao) có policySnapshot.risks nên form bên thuê chỉ hiện ô rủi ro (checkbox), không | 4 ảnh |
| B06a-fix3 | Fix 5 thật: khiếu nại RENTER_LOGIN_FAILED, form hỗ trợ không có ô khấu trừ (có đối chứng) | PASS | PASS: đơn 992a4408 (listing 4af30b11, account 7a0eee4a xoay vòng trước khi đặt; IN_RENTAL; chưa bàn giao). Xem thông tin đăng nhập: HTTP 200, credentialFirstViewedAt=2026-11-22T05:10:52.044654Z. Khiếu nại RENTER_LOGIN_FAILED 54d9b293 HTTP 201. Form hỗ trợ (dữ liệu thật): #dec=1, #ded=0. Đối chứng (p | 2 ảnh |
| B06a-fix2 | Fix 5 đơn lẻ: khiếu nại RENTER_LOGIN_FAILED, form hỗ trợ không có ô khấu trừ | ĐÃ THAY THẾ bởi B06a-fix3 (BLOCKED gốc → B06a-fix3) | ĐÃ THAY THẾ bởi B06a-fix3. Nguyên nhân bị từ chối: đơn chưa từng xem thông tin đăng nhập nên cửa sổ khiếu nại chưa mở (credentialFirstViewedAt rỗng), không phải do thời gian. BLOCKED: POST /orders/e2408e83/disputes với type RENTER_LOGIN_FAILED bị từ chối HTTP 422, mã COMPLAINT_WINDOW_CLOSED: Đã hết | 0 ảnh |

## 3. Phát hiện và trạng thái sau kiểm thử

Phần này tóm tắt `findings.md` của lần kiểm thử. Quyết định sản phẩm do advisor đưa ra theo ủy quyền của người dùng, người dùng có thể đảo lại.

### 3.1 Đã sửa, đã xác nhận trên trình duyệt

- Mã game dùng ký tự an toàn trong URL (`c8399c7`): A11-fix PASS.
- Trang duyệt không gọi `/games?all=true` khi thiếu `game:read_hidden` (`b956150`): FX2 PASS, có đối chứng admin@. Người tạo yêu cầu không thấy nút Duyệt: FX3 PASS.
- Nút nhận account chỉ hiện khi đơn `IN_RENTAL` (`0a07117`): B01-fix và A43-fix PASS.
- Khiếu nại phía người thuê không có ô khấu trừ cọc (`22da884`): B06a-fix3 PASS, có đối chứng khi đổi loại thành OWNER_DAMAGE thì ô hiện.
- Lý do từ chối cũ không còn hiện trên tin đã duyệt (A20, `5e223dd`): FX5-stale-note PASS.
- Trang quản lý tin không gọi game ẩn khi thiếu quyền (`5e223dd`): FX4-finance và FX4-admin-control PASS.
- Luồng OTP sau khi sửa (`5e223dd`): FX6 và FX6-in-rental PASS. Điều kiện: đồng hồ dev phải nằm trong cửa sổ xem thông tin đăng nhập (`startAt` đến `block_end_at`). Chạy ở giờ thật trả 422 `CREDENTIAL_WINDOW_CLOSED`, đây là hệ quả của cách dựng dữ liệu, không phải lỗi đã xác định.
- Dòng nhắc trên trang đơn: khiếu nại đăng nhập/không đúng mô tả cần đã xem thông tin đăng nhập. FX7 PASS (người thuê thấy dòng nhắc, chủ không thấy).

### 3.2 Đã quyết định, không đổi

- Phạt chủ account: không thêm. Hệ thống không có bảng phạt hay cảnh cáo, chỉ có `abuse_signals`. Thêm phạt cần migration; sáu tín hiệu `OWNER_FAULT_NOT_READY` đều sinh từ lần kiểm thử này. Người dùng có thể đảo lại.
- Logout-all, hoàn tiền `RENTER_RISK`, trạng thái hoàn tiền đang chờ, top-up dồn, `riskNotes`, và 422 khi đã có gia hạn đã lưu: giữ nguyên.
- `releaseDue`: không lệch. Job chỉ lấy `POST_RENTAL_HOLD`. Cọc có tranh chấp `OWNER_DAMAGE` hoặc `ACCOUNT_TAKEOVER` chuyển sang `DISPUTED` và bị loại theo trạng thái (kiểm bằng `DisputeIT.ownerDamage_partialDeduction`). Chỉ sửa Javadoc `releaseDeposits` (`3d77329`).

### 3.3 Chưa giải quyết

- Ghi chú khiếu nại: khi người thuê chưa xem thông tin đăng nhập, khiếu nại loại đăng nhập bị từ chối `422 COMPLAINT_WINDOW_CLOSED`. Đây là hành vi đúng của backend. Có thể thêm gợi ý "hãy xem thông tin đăng nhập trước" trên form (tùy chọn).
- A17: tài khoản `finance@` nhận 403 từ `GET /api/v1/games` khi giao diện tải danh sách game (ma trận ghi là menu/trang tải danh sách game). Chưa xác định vì sao; cần xem lại RBAC của endpoint này.
- Sổ cái và số dư ví: `created_at` không khớp thứ tự commit. Báo cáo sắp theo `created_at` có thể hiện số âm giả (ví dụ −488.000 ₫ cho một ví mà số dư thật không bao giờ âm). Số dư đúng tính theo thứ tự commit. Số liệu 357.000 ₫ ghi trước đó không tái tạo được.

## 4. Chưa kiểm thử được

- **C01** PSP thật, **C02** R2/S3 thật, **C04** app iOS/Android: BLOCKED. Cần khóa, bucket và simulator mà container không có. Bản S3 cục bộ bị từ chối quyền cài công cụ.
- **C03** email: NOT_IMPLEMENTED. Backend không có đường gửi email: không có dependency SMTP, không có lớp gửi thư, không có luồng quên mật khẩu hay xác minh email.
- **MFA1** bật TOTP cho tài khoản nhân viên: BLOCKED. Bộ phân loại quyền từ chối lệnh bật TOTP cho `finance@`. Không thử cách khác. Cần người dùng cho phép rõ ràng nếu muốn chạy. A22 kiểm được phần đăng nhập có mã trên backend cổng 8081 với `RENTGG_STAFF_MFA_REQUIRED=true`; phần đăng ký chưa kiểm lại trong lần này.

## 5. Thay đổi trạng thái trên DB dev

Các thao tác dưới đây đã ghi vào DB dev. Không ảnh hưởng production, nhưng dữ liệu dev không còn ở trạng thái ban đầu:
- Tua đồng hồ dev bảy lần tới 26/11, tất cả đã reset về offset 0. Trong thời gian tua, job chạy và đã đổi một số bút toán, giải phóng cọc của đơn `e2408e83` và `992a4408` (vượt `hold_until` 25/11), và tạo thêm 1 tín hiệu `ROTATION_OVERDUE` cho chủ (tổng 9).
- Xoay vòng mật khẩu account `7a0eee4a` bằng mật khẩu mock. Đặt và hủy đơn `8375b797`. Bốn game `otpfx6-*` và các đơn FX6 vẫn còn trong DB.
- Tranh chấp `54d9b293` và `f33b5109` vẫn `OPEN`.
- Các giá trị số dư đọc lại ngày 22/11 nằm trong nhật ký phát hiện; số cũ không còn đối chiếu được.

## 6. Ghi chú về phương pháp

- Một số báo cáo trước đó nói OTP "chỉ theo mã" là sai. Lỗi thật là cache token của `apiLogin` và cửa sổ thuê mở 26/11. Đã sửa bằng `fxlogin.cjs`, không phải lỗi sản phẩm.
- Lần chụp đầu của A43-fix ghi "nút bàn giao không hiện". Lỗi là do chụp trước khi dữ liệu tải xong. Lần kiểm tra có chờ 15 giây cho thấy nút hiện đúng. Đã loại khỏi danh sách lỗi.
- Một số hàng dùng bước phụ (ví dụ A46 bấm nhầm nút "Giả lập" đầu tiên trong danh sách). Mô tả đầy đủ nằm trong cột Chi tiết, giữ nguyên để có lịch sử.

## Liên quan

- `docs/RentGG_Staff_Guide.md` mục 6 liệt kê ID theo vai trò, lấy từ bảng này.
- `docs/RentGG_Validation_Checklist.md` là checklist của đội trước khi ra mắt. Báo cáo này không thay thế checklist đó.
- `docs/README.md` là mục lục.
