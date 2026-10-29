# RentGG — Use Case Specification

**Tài liệu:** Bản Use Case mục tiêu theo mô hình tính năng công khai của các nền tảng Trung Quốc, trọng tâm Zuhaowan (租号玩), Zuhaoxiu (租号秀) và đối chiếu bổ sung với GG租号.  
**Phiên bản:** v8 (2026-10-26) — khớp với `RentGG_Decisions.md` (bản chốt quyết định chung cho tài liệu và backend).  
**Trạng thái:** v8 — Đặc tả đã chốt cho MVP (Phase 0) / Backend đang triển khai theo bản chốt quyết định / Vẫn cần thẩm định pháp lý và sản phẩm trước khi phát hành.  
**Phạm vi:** Marketplace kết nối chủ account với người thuê, bao gồm listing/kệ hàng, merchant, đặt lịch, thanh toán, bàn giao credentials trực tiếp cho người thuê (không có client/launcher), tiền đặt cọc bắt buộc của người thuê, xác minh quyền kiểm soát thông tin khôi phục của chủ account, quỹ bồi hoàn sai thông tin của người đăng, phân xử, số dư/chi trả, khuyến mại và gói bảo vệ account bị khóa.  
**Thay đổi yêu cầu:** Người dùng muốn xây dựng bản thiết kế có **đầy đủ các nhóm chức năng tương tự mô hình Trung Quốc, kể cả tiền đặt cọc và gói bảo vệ account**. Các tính năng được đưa vào đặc tả mục tiêu; triển khai theo giai đoạn và chỉ mở chức năng liên quan tiền hoặc bảo hiểm sau khi xác nhận điều kiện pháp lý, đối tác, bảo mật và quyền riêng tư tại Việt Nam.  

**Thay đổi so với v7:**
1. **Bỏ hoàn toàn Client/launcher (R1).** Bàn giao credentials trực tiếp cho người thuê sau khi đơn hợp lệ; UC-28 được đánh dấu "Ngoài phạm vi (đã loại bỏ)"; mọi chỗ phụ thuộc client (mục tiêu, nguyên tắc §3, UC-08, UC-12, UC-14, UC-37, UC-39, UC-42) được sửa.
2. **UC-08 bàn giao trực tiếp:** chế độ bàn giao theo game `STATIC_CREDENTIAL` | `OWNER_ASSISTED_OTP`; chỉ người thuê của đơn xem credentials trong cửa sổ [`start_at`, `block_end_at`]; lần xem đầu bắt đầu complaint window; audit mỗi lần xem; nhân viên không xem được mật khẩu. `AUTO_ACCEPT` không bật được với `OWNER_ASSISTED_OTP` (UC-38).
3. **R2 — chủ account bắt buộc nắm giữ thông tin khôi phục** (email, SĐT, 2FA): bổ sung vào §3, UC-04, UC-30 và UC mới **UC-44** (P0).
4. **R3/R4/R5 — cọc bắt buộc với mọi listing:** viết lại UC-25 (P0) với trạng thái cọc chuẩn, giữ cọc sau thuê `deposit_hold_hours` (mặc định 72 giờ), khiếu nại thiệt hại trong thời gian giữ, tự hoàn khi hết hạn; cọc gắn 1–1 với đơn — đang bị giữ cọc đơn A mà thuê đơn B thì phải đặt cọc mới cho B. Cập nhật UC-06, UC-07, UC-27 (P0), UC-33, UC-41.
5. **Phí nền tảng** là hoa hồng trừ từ tiền thuê của chủ account (`platform_fee_bps`, mặc định 10%); người thuê trả tiền thuê + cọc (UC-07).
6. **UC-37:** đổi mật khẩu là cơ chế kết thúc phiên duy nhất; đổi tên chế độ thành `FORCE_AT_END` / `GRACE_FOR_CURRENT_MATCH` / `STAY_ONLINE_UNTIL_RECLAIM`; phụ thuộc `password_change_kicks_sessions`.
7. **UC-39:** rotation thủ công là mặc định MVP; trạng thái GameAccount theo bản chốt; SLA rotation 60 phút; đơn kế tiếp đến giờ mà account chưa `AVAILABLE` → hủy, hoàn 100%, ghi tín hiệu lạm dụng chủ account.
8. **UC mới UC-43** (P0): người thuê chiếm account trong lúc thuê (đổi mật khẩu/thông tin khôi phục).
9. **Bảng ưu tiên §4:** UC-25, UC-27, UC-40 thành P0; UC-17 tách P0 (đặt trước, hủy) / P1 (gia hạn); UC-26 thành P2 theo lộ trình; thêm UC-43, UC-44.
10. **Thống nhất tên trạng thái đơn** (`PENDING_PAYMENT`, `AWAITING_OWNER_APPROVAL`, `CONFIRMED`, `IN_RENTAL`, `ENDED`, `CANCELLED` + `cancel_reason`, `EXPIRED`, `REJECTED`) và thêm **Phụ lục — Trạng thái chuẩn** cuối tài liệu.  

> “Mô phỏng toàn bộ mô hình” ở đây có nghĩa là mô phỏng các luồng nghiệp vụ/chức năng đã công khai, không sao chép mã nguồn, thương hiệu, nội dung độc quyền hay điều khoản nguyên văn. Tham số tiền, phí, thời hạn, mức phạt và điều kiện bồi thường của nền tảng Trung Quốc không tự động áp dụng cho Việt Nam. ToS/quyền được cho thuê phải được kiểm tra theo từng game; không thiết kế tính năng nhằm lẩn tránh xác thực hoặc anti-cheat của nhà phát hành.

---

## 1. Mục tiêu sản phẩm

1. Cho chủ account đăng tin cho thuê, đặt giá và quản lý lịch khả dụng.
2. Cho người thuê tìm, so sánh, đặt thời gian, thanh toán và nhận hướng dẫn truy cập.
3. Quản lý vòng đời đơn: tạo đơn → thanh toán → bàn giao → sử dụng → kết thúc → xử lý khiếu nại → quyết toán.
4. Giảm thiệt hại do cheat, phá rank, tiêu hao vật phẩm, đổi thông tin, chiếm đoạt account, đăng nhập sai hoặc khiếu nại gian lận.
5. Xây dựng cơ chế uy tín, xử phạt, lưu bằng chứng và phân xử nhất quán.
6. Mô phỏng đầy đủ các nhóm chức năng công khai của nền tảng Trung Quốc — bao gồm đặt cọc bắt buộc, bồi hoàn lỗi listing, merchant/shop, bàn giao credentials trực tiếp có kiểm soát (không dùng client/launcher), phân xử, số dư/chi trả, khuyến mại và gói bảo vệ account bị khóa — đồng thời điều chỉnh điều kiện triển khai cho Việt Nam.
7. Không mặc định rằng một game được phép cho thuê chỉ vì nền tảng khác đang đăng game đó. Mỗi game phải có trạng thái kiểm tra ToS/quyền sử dụng riêng.

## 2. Tác nhân

| Tác nhân | Trách nhiệm |
|---|---|
| Khách vãng lai | Xem danh mục, tìm kiếm, xem chi tiết account và quy tắc thuê. |
| Người thuê | Xác minh tài khoản, đặt thuê, thanh toán, sử dụng đúng phạm vi, báo sự cố/khiếu nại. |
| Chủ account | Xác minh danh tính, khai báo quyền cung cấp account, tạo tin, đặt giá/lịch, bàn giao và xử lý sự cố. |
| Chủ account đã xác minh / Merchant | Nhóm người bán có thể được cấp hạn mức đăng tin và công cụ quản lý nâng cao sau khi duyệt. |
| Quản trị viên / kiểm duyệt | Duyệt tin, quản lý game được niêm yết, xử lý vi phạm, khóa tài khoản, quản lý phí và cấu hình quy tắc. |
| Nhân viên hỗ trợ / phân xử | Tiếp nhận khiếu nại, xem bằng chứng, quyết định hoàn tiền/khấu trừ theo chính sách và ghi lý do. |
| Cổng thanh toán / PSP | Xử lý thanh toán, hoàn tiền và chi trả theo tích hợp được pháp luật và nhà cung cấp cho phép. |
| Nhà phát hành game | Không phải người dùng trực tiếp của RentGG; có thể là bên gửi thông báo về ToS, nhãn hiệu, bản quyền hoặc hành vi vi phạm. |
| Đối tác bảo hiểm / đơn vị bảo vệ tài sản (mở rộng) | Chỉ tham gia nếu có sản phẩm bảo hiểm/bảo vệ được thẩm định và ký kết hợp lệ. Không mặc định RentGG tự cung cấp bảo hiểm. |

## 3. Các nguyên tắc mô phỏng mô hình Trung Quốc

- **Listing + kệ hàng:** mỗi account có trang tin riêng, ảnh minh họa, thuộc tính, giá, lịch khả dụng và quy tắc sử dụng.
- **Tự động nhận đơn theo ủy quyền có trước:** chủ account bật `AUTO_ACCEPT` cho từng listing và xác nhận trước các điều kiện như giá, lịch, thời lượng, đặt cọc, giới hạn sử dụng và chính sách hủy. Đơn phù hợp được tự động xác nhận sau khi hệ thống kiểm tra điều kiện và thanh toán/ủy quyền thanh toán thành công; đây là sự đồng ý có trước của chủ account, không phải tự ý giao account khi chủ chưa bật chế độ.
- **Đổi mật khẩu sau hủy/hết hạn:** khi thông tin truy cập đã được cấp, hoặc credential có thể đã bị lộ, GameAccount chuyển `ROTATION_PENDING` và chủ account đổi mật khẩu (rotation thủ công là mặc định MVP, SLA 60 phút), tạo phiên bản credential mới, rồi mới cho account `AVAILABLE` trở lại. Không có client nên đổi mật khẩu là cơ chế kết thúc phiên duy nhất. Tự động đổi mật khẩu chỉ cân nhắc ở Phase sau khi game có cơ chế hợp lệ; tuyệt đối không vượt qua MFA, email/điện thoại khôi phục hoặc cơ chế bảo mật của nhà phát hành.
- **Chủ account nắm giữ thông tin khôi phục (R2):** chủ account bắt buộc kiểm soát email khôi phục, số điện thoại và 2FA/xác thực của account game; người thuê không bao giờ được cung cấp các thông tin này. Chủ account phải cam kết (attest) và admin xác minh trước khi GameAccount chuyển từ `PENDING_VERIFICATION` sang `AVAILABLE` và được dùng để đăng listing (UC-44). Đây là lớp bảo vệ chính để chủ account lấy lại account nếu người thuê đổi mật khẩu (UC-43).
- **Đặt thời gian rõ ràng:** đơn có thời điểm bắt đầu/kết thúc; giữ chỗ để tránh hai người thuê cùng account một lúc.
- **Bàn giao trực tiếp có kiểm soát:** không có client/launcher. Credentials (username + mật khẩu, mã hóa AES-256-GCM khi lưu) chỉ hiển thị cho người thuê của đơn, chỉ khi đơn ở `IN_RENTAL` và trong cửa sổ [`start_at`, `block_end_at`]; mỗi lần xem được audit và giới hạn tần suất; nhân viên hỗ trợ/admin không có chức năng xem mật khẩu. Tuyệt đối không lưu mật khẩu ở dạng văn bản thuần hoặc tiết lộ dữ liệu không cần thiết (UC-08).
- **Cửa sổ khiếu nại:** phân loại lỗi đăng nhập, sai mô tả, nhầm account/region, bị chiếm phiên và hành vi vi phạm; đặt thời hạn báo lỗi cấu hình theo game/loại thuê, hiển thị trước khi thanh toán.
- **Bằng chứng trước khi phạt:** không phạt chỉ vì có cáo buộc. Lưu trạng thái đơn, thời điểm, ảnh/chứng từ và lịch sử trao đổi; cho phép phản hồi/kháng nghị.
- **Chế tài nhiều cấp:** cảnh báo → hủy phiên/đơn → hoàn tiền hoặc khấu trừ trong phạm vi quy định → hạn chế thuê/đăng tin → đình chỉ/vĩnh viễn, tùy mức độ và chứng cứ.
- **Phát hiện cheat có giới hạn:** chỉ triển khai kiểm tra kỹ thuật khi có cơ sở pháp lý, minh bạch và sự đồng ý phù hợp. Không giả định có thể phát hiện 100% cheat, không thu thập quá mức, không vượt qua cơ chế bảo mật của game.
- **Bảo vệ khi account bị khóa:** tách tranh chấp người thuê gây ra với việc nhà phát hành khóa do ToS hoặc nguyên nhân khác. Chỉ kết luận nguyên nhân khi có đủ chứng cứ.
- **Quyền niêm yết theo game:** trạng thái `ALLOWED`, `REVIEW_REQUIRED`, `PROHIBITED` hoặc `SUSPENDED`. Game bị cấm rõ ràng hoặc có yêu cầu hợp lệ từ chủ thể quyền phải được chặn/gỡ trong khi xem xét; không dùng disclaimer để thay thế quyền được cho thuê.
- **Hết giờ khi còn trong trận:** mô hình công khai của Zuhaoxiu có cộng thêm 10 phút vào thời gian thuê; listing có thể bật chế độ `到时不下线` để không đẩy người dùng ra ngay khi hết giờ, nhưng phiên có thể bị kết thúc khi chủ account hoặc người thuê tiếp theo cần sử dụng. Nếu không bật, người thuê bị đẩy ra khi hết giờ. GG租号 yêu cầu người thuê thoát đúng hạn và chịu trách nhiệm về thiệt hại do không thoát. RentGG phải có chính sách riêng cho gia hạn, ân hạn và kết thúc phiên; không mặc định người thuê được chơi vô hạn đến hết trận. Vì RentGG không có client, việc "đẩy ra" chỉ thực hiện được bằng đổi mật khẩu (UC-37).
- **Đặt cọc bắt buộc, gắn 1–1 với đơn (R3/R4/R5):** mọi listing bắt buộc có tiền cọc; chủ account tự đặt mức trong khoảng [`deposit_min`, `deposit_max`] cấu hình theo game. Người thuê thanh toán cọc cùng tiền thuê trong một lần thanh toán mới tạo được đơn hợp lệ. Cọc ghi riêng ở sổ `DEPOSIT_HELD` (nợ phải trả người thuê, không phải doanh thu). Sau khi đơn kết thúc, cọc được giữ thêm `deposit_hold_hours` (mặc định 72 giờ) để chủ account mở khiếu nại thiệt hại; hết hạn không tranh chấp thì tự hoàn. Hủy trước khi bắt đầu thì hoàn cọc ngay, không giữ. Mỗi đơn có cọc riêng: không có "số dư cọc" dùng chung, không dùng lại/chuyển cọc giữa các đơn, không bù chéo; người thuê còn đang bị giữ cọc đơn trước mà thuê account khác thì phải đặt cọc mới (UC-25, UC-27).
- **Quỹ bồi hoàn lỗi listing của người đăng:** mô phỏng tính năng “赔付” trên nền tảng Trung Quốc: người đăng có thể chọn đóng một khoản bảo đảm nhỏ cho các lỗi được định nghĩa rõ như mật khẩu sai hoặc mô tả sai nghiêm trọng; khoản này bồi hoàn cho người thuê nếu phân xử xác nhận lỗi thuộc người đăng. Đây là khoản khác với tiền đặt cọc của người thuê.
- **Bảo vệ khi account bị khóa:** có luồng yêu cầu bảo vệ theo tiêu chí định lượng, nhưng chỉ được thương mại hóa dưới tên “bảo hiểm” khi có doanh nghiệp bảo hiểm/đối tác được phép và hợp đồng hợp lệ. Nếu RentGG tự tài trợ, phải gọi đúng bản chất là quỹ/chương trình bảo vệ và thẩm định riêng.
- **Không có Client/launcher (R1):** RentGG không phát triển launcher đăng nhập, không cài phần mềm giám sát hay chụp màn hình trên máy người thuê. Rủi ro cheat/phá hoại được kiểm soát bằng: cọc bắt buộc, cam kết thông tin khôi phục của chủ account, bàn giao credentials có audit, rotation mật khẩu sau mỗi đơn, báo cáo có bằng chứng và phân xử (UC-11, UC-12, UC-25, UC-27, UC-39, UC-43, UC-44). Không thiết kế bất kỳ cơ chế nào vượt qua bảo vệ của game.
- **Ví, số dư khuyến mại và payout:** mô hình mục tiêu có ledger phân tách số dư khả dụng, số dư bị giữ, tiền đặt cọc, tiền thuê, hoàn tiền, coupon/điểm thưởng và payout. Không tự phát hành ví điện tử hoặc nắm giữ/điều chuyển tiền theo cách cần giấy phép nếu chưa có cấu trúc PSP/pháp lý phù hợp.
- **Tự động hóa có can thiệp thủ công (human override):** mọi quy trình tự động (nhận đơn, đổi mật khẩu, kết thúc phiên, giữ/giải phóng tiền, phát hiện cheat, kiểm duyệt) phải có điểm dừng và đường can thiệp thủ công được kiểm soát: đúng vai trò, có lý do, có phạm vi và thời hạn, có audit log, thông báo cho bên liên quan và quyền kháng nghị. Can thiệp thủ công không được dùng để vượt qua các kiểm soát bảo mật, pháp lý hoặc tài chính bắt buộc (xem UC-42).

## 4. Danh mục Use Case

Ưu tiên: **P0 = cần cho MVP**, **P1 = sau MVP**, **P2 = chỉ mở khi có đối tác/pháp lý/công nghệ phù hợp**.

| ID | Use Case | Tác nhân chính | Ưu tiên |
|---|---|---|---|
| UC-01 | Đăng ký, đăng nhập và xác minh người dùng | Người thuê / Chủ account | P0 |
| UC-02 | Duyệt danh mục, tìm kiếm và lọc account | Khách / Người thuê | P0 |
| UC-03 | Kiểm tra điều kiện ToS/quyền niêm yết theo game | Admin / Kiểm duyệt | P0 |
| UC-04 | Tạo và quản lý tin cho thuê account | Chủ account | P0 |
| UC-05 | Kiểm duyệt tin và gỡ tin vi phạm | Admin / Kiểm duyệt | P0 |
| UC-06 | Chọn thời lượng, giữ lịch và tạo đơn thuê | Người thuê | P0 |
| UC-07 | Thanh toán, xác nhận đơn và tính phí nền tảng | Người thuê / PSP | P0 |
| UC-08 | Bàn giao credentials trực tiếp sau khi đơn hợp lệ (`STATIC_CREDENTIAL` / `OWNER_ASSISTED_OTP`) | Người thuê / Chủ account | P0 |
| UC-09 | Theo dõi thời gian thuê, kết thúc phiên và trả account | Hệ thống / Hai bên | P0 |
| UC-10 | Báo lỗi đăng nhập/sai mô tả/nhầm account | Người thuê / Hỗ trợ | P0 |
| UC-11 | Báo cáo người thuê phá hoại hoặc vi phạm | Chủ account | P0 |
| UC-12 | Phát hiện/tiếp nhận cáo buộc cheat và hành vi nguy hiểm | Hệ thống / Hỗ trợ | P1 |
| UC-13 | Phân xử tranh chấp và quyết định hoàn tiền/khấu trừ | Hỗ trợ / Admin | P0 |
| UC-14 | Xếp hạng uy tín và kiểm soát tái phạm | Hệ thống / Admin | P1 |
| UC-15 | Chi trả doanh thu cho chủ account | Hệ thống / PSP / Chủ account | P0 |
| UC-16 | Tạo cấp Merchant / shop và hạn mức đăng tin | Chủ account / Admin | P1 |
| UC-17 | Đặt lịch trước, gia hạn và hủy đơn | Người thuê / Chủ account | P0 (đặt trước, hủy); P1 (gia hạn) |
| UC-18 | Khiếu nại account bị khóa và gói bảo vệ/bồi thường | Chủ account / Hỗ trợ / Đối tác | P2 |
| UC-19 | Quản lý nhiều kênh và đồng bộ tồn kho | Merchant / Hệ thống | P2 |
| UC-20 | Tiếp nhận thông báo nhà phát hành, rà soát quyền và gỡ nội dung | Admin / Pháp chế | P0 |
| UC-21 | Quản lý người chưa thành niên và an toàn dịch vụ | Hệ thống / Admin | P0 |
| UC-22 | Trung tâm hỗ trợ, tra cứu đơn và nhật ký kiểm toán | Người dùng / Hỗ trợ / Admin | P0 |
| UC-23 | Cấu hình phí, danh sách game và chính sách theo từng game | Admin | P0 |
| UC-24 | Số dư, nạp/rút tiền và ledger giao dịch | Người dùng / PSP / Finance | P1/P2, thẩm định trước |
| UC-25 | Tiền đặt cọc bắt buộc của người thuê: thiết lập, thu, giữ sau thuê và hoàn | Chủ account / Người thuê / PSP / Hệ thống | P0 |
| UC-26 | Quỹ bồi hoàn sai thông tin do người đăng lựa chọn | Chủ account / Người thuê / Hệ thống | P2 |
| UC-27 | Phân xử, khấu trừ và hoàn trả đặt cọc | Hỗ trợ / PSP / Hai bên | P0 |
| UC-28 | ~~Client đăng nhập và chống cheat có thông báo~~ | — | Ngoài phạm vi (đã loại bỏ ở v8; thay bằng UC-08 + UC-39) |
| UC-29 | Gói bảo vệ account bị khóa / khiếu nại bảo hiểm | Chủ account / Đối tác / Hỗ trợ | P2, cần đối tác hợp lệ |
| UC-30 | Kiểm tra trạng thái account trước thuê và bảo vệ tài sản | Chủ account / Người thuê | P0 |
| UC-31 | Merchant/shop, tier, bảng phí và công cụ quản lý | Merchant / Admin | P1 |
| UC-32 | Coupon, red packet, rent-and-gift, free trial và quảng bá | Người dùng / Merchant / Admin | P1/P2 |
| UC-33 | Cửa sổ kiểm tra, hủy đơn và quy tắc hoàn tiền | Người thuê / Chủ account / Hỗ trợ | P0 |
| UC-34 | Đồng bộ kho account giữa nhiều kênh | Merchant / Hệ thống | P2 |
| UC-35 | Chất lượng listing, ảnh chứng minh và xếp hạng tin | Chủ account / Hệ thống | P1 |
| UC-36 | Lịch quyết toán, thời gian chờ payout và rút tiền | Chủ account / Finance / PSP | P0 về quy tắc; P1/P2 về payout thực tế |
| UC-37 | Xử lý hết giờ thuê khi người thuê vẫn đang trong trận / chưa đăng xuất | Người thuê / Chủ account / Hệ thống / Hỗ trợ | P0 |
| UC-38 | Tự động match nhu cầu và nhận đơn theo listing đã bật AUTO_ACCEPT | Người thuê / Chủ account / Hệ thống / PSP | P0 |
| UC-39 | Thu hồi quyền truy cập và đổi mật khẩu (rotation) khi hủy đơn hoặc hết giờ | Hệ thống / Chủ account / Người thuê / Hỗ trợ | P0 (rotation thủ công); tự động là Phase sau nếu game có cơ chế hợp lệ |
| UC-40 | Chủ account hủy lịch đã được xác nhận trước giờ bắt đầu | Chủ account / Người thuê / Hệ thống / PSP / Hỗ trợ | P0 |
| UC-41 | Giữ tiền sau thuê, kiểm soát rủi ro và điều kiện cho phép rút | Chủ account / Hệ thống / Finance / Hỗ trợ / PSP | P0 về policy; payout phụ thuộc PSP/pháp lý |
| UC-42 | Can thiệp thủ công trên các quy trình tự động (human override) | Chủ account / Hỗ trợ / Admin / Finance / Hệ thống | P0 |
| UC-43 | Renter chiếm account trong lúc thuê (đổi mật khẩu/thông tin khôi phục) | Chủ account / Hỗ trợ / Admin / Hệ thống | P0 |
| UC-44 | Xác minh quyền kiểm soát thông tin khôi phục của chủ account | Chủ account / Admin / Hệ thống | P0 |

---

# 5. Đặc tả Use Case chi tiết

## UC-01 — Đăng ký, đăng nhập và xác minh người dùng

**Mục tiêu:** gắn hành vi thuê/cho thuê với một tài khoản có thể truy vết và giảm khả năng tái phạm bằng tài khoản mới.

**Tiền điều kiện:** người dùng truy cập RentGG và đồng ý các điều khoản, thông báo dữ liệu cần thiết.

**Luồng chính:**
1. Người dùng đăng ký bằng phương thức được hỗ trợ.
2. Hệ thống xác minh email/số điện thoại và thiết lập bảo mật đăng nhập.
3. Khi muốn thuê hoặc đăng tin, hệ thống yêu cầu bổ sung thông tin xác minh tương ứng với mức rủi ro và yêu cầu pháp lý.
4. Người dùng đồng ý điều khoản marketplace, chính sách quyền riêng tư, quy tắc thuê và xử lý tranh chấp.
5. Hệ thống ghi trạng thái xác minh và giới hạn chức năng theo trạng thái.

**Ngoại lệ:** xác minh thất bại → chưa cho giao dịch; có dấu hiệu trùng tài khoản gian lận → chuyển kiểm tra thủ công; người dùng không đủ điều kiện tuổi → không cung cấp tính năng bị giới hạn.

**Quy tắc:** thu thập tối thiểu dữ liệu cần thiết; không dùng thông tin xác minh cho mục đích khác nếu không có căn cứ phù hợp.

## UC-02 — Duyệt, tìm kiếm và lọc account

**Mục tiêu:** cho người thuê tìm account phù hợp mà không cần hỏi trực tiếp từng chủ account.

**Luồng chính:**
1. Người thuê chọn game/khu vực/kiểu account.
2. Hệ thống lọc theo giá, rank, vật phẩm, loại thuê, lịch trống, đánh giá, tỷ lệ đơn hoàn thành và trạng thái tin.
3. Trang chi tiết hiển thị mô tả, ảnh đã rà soát, giá, thời lượng, nội quy, giới hạn sử dụng, điều kiện hủy và cảnh báo rủi ro đã xác minh.
4. Người thuê xem điều khoản riêng của game và điều khoản giao dịch RentGG.
5. Người thuê chọn tin và chuyển sang UC-06.

**Ngoại lệ:** account vừa được người khác đặt → thông báo hết khả dụng; tin đang rà soát/đã bị gỡ → không cho đặt; game có trạng thái `REVIEW_REQUIRED` → không nhận đơn mới cho đến khi hoàn tất kiểm tra.

## UC-03 — Kiểm tra ToS/quyền niêm yết theo từng game

**Mục tiêu:** tránh coi ToS của mọi game giống nhau và có hồ sơ chứng minh cách RentGG ra quyết định.

**Luồng chính:**
1. Admin tạo hồ sơ game gồm nhà phát hành, khu vực, liên kết ToS/Community Guidelines, ngày kiểm tra và người kiểm tra.
2. Người phụ trách đánh giá điều khoản về chia sẻ, cho thuê, mua bán, đăng nhập bên thứ ba, thương mại hóa, nhãn hiệu/hình ảnh và giới hạn độ tuổi.
3. Admin gán trạng thái: `ALLOWED`, `REVIEW_REQUIRED`, `PROHIBITED` hoặc `SUSPENDED`, kèm lý do và ngày rà soát tiếp theo.
4. Hệ thống áp trạng thái này vào tạo tin và đặt thuê.
5. Khi có ToS mới, thông báo nhà phát hành hoặc tranh chấp, hồ sơ được rà soát lại.

**Ngoại lệ:** chưa rõ điều khoản hoặc chưa có căn cứ quyền → `REVIEW_REQUIRED`; có thông báo pháp lý cần xử lý khẩn cấp → tạm ẩn tin/giao dịch liên quan trong lúc xem xét.

**Quy tắc:** điều khoản miễn trừ không thay thế quyền được cho thuê; không xây dựng chức năng nhằm lẩn tránh xác thực hoặc thực thi ToS.

## UC-04 — Chủ account tạo tin cho thuê

**Mục tiêu:** chủ account đăng thông tin đầy đủ, nhất quán và kiểm soát rủi ro trước khi mở cho thuê.

**Tiền điều kiện:**
- Người đăng đủ 18 tuổi, `kyc_status = VERIFIED`; game ở trạng thái `ALLOWED`.
- GameAccount của listing đã được tạo kèm credentials (username + mật khẩu, lưu mã hóa) và **cam kết thông tin khôi phục** (`recovery_email_owned`, `recovery_phone_owned`, `two_factor_owned`), đồng thời **đã được admin xác minh** và ở trạng thái `AVAILABLE` (UC-44). GameAccount còn `PENDING_VERIFICATION` không được dùng để gửi listing đi duyệt.
- GameAccount chưa có listing `ACTIVE` khác (mỗi GameAccount tối đa 1 listing `ACTIVE`).

**Luồng chính:**
1. Chủ account tạo GameAccount (nếu chưa có): chọn game, server/region, nhập username/mật khẩu và tick từng cam kết rằng mình đang nắm giữ email khôi phục, số điện thoại và 2FA/xác thực của account; người thuê sẽ không được cung cấp các thông tin này. Hệ thống lưu `attested_at`, chuyển GameAccount sang `PENDING_VERIFICATION` và đưa vào hàng chờ xác minh (UC-44).
2. Chủ account chọn GameAccount đã `AVAILABLE` và loại account để tạo listing.
3. Nhập thuộc tính: rank, nhân vật/vật phẩm liên quan, nền tảng thiết bị, giới hạn chế độ chơi, thời lượng tối thiểu/tối đa (trong [`min_duration_minutes`, `max_duration_minutes`] của game), giá theo giờ, lịch trống, điều kiện gia hạn/hủy, chế độ kết thúc phiên (`end_mode`) và quy tắc sử dụng.
4. Nhập **mức cọc bắt buộc** (`deposit_amount` > 0, trong [`deposit_min`, `deposit_max`] của game) theo UC-25; chọn `auto_accept` (bị chặn nếu game dùng `OWNER_ASSISTED_OTP`, xem UC-38).
5. Tải ảnh/chứng cứ sở hữu hoặc quyền cung cấp ở mức cần thiết; hệ thống nhắc che email, số điện thoại, ID cá nhân và thông tin khôi phục không cần công khai.
6. Chủ account xác nhận có quyền đăng tin và đồng ý trách nhiệm cung cấp thông tin chính xác.
7. Hệ thống kiểm tra trường bắt buộc, trùng lặp, từ khóa cấm, trạng thái game, trạng thái xác minh thông tin khôi phục và khoảng cọc hợp lệ.
8. Listing chuyển `DRAFT` → `PENDING_REVIEW` khi gửi duyệt; admin duyệt thì thành `ACTIVE` (UC-05).
9. Chủ account theo dõi lượt xem, đơn, lịch trống, khiếu nại và doanh thu.

**Ngoại lệ:** mô tả không đầy đủ → yêu cầu sửa; GameAccount chưa xác minh thông tin khôi phục → không cho gửi duyệt, hiển thị trạng thái chờ xác minh; mức cọc ngoài khoảng cho phép hoặc bằng 0 → từ chối lưu; nghi ngờ account bị chiếm đoạt hoặc không có quyền cung cấp → từ chối/đình chỉ; game đổi ToS → tạm ẩn đến khi đánh giá lại.

**Quy tắc nghiệp vụ:**
1. Không có listing không cọc; không có listing trên GameAccount chưa qua xác minh thông tin khôi phục.
2. GameAccount là đơn vị khóa lịch và rotation, không phải listing; đổi listing không làm mất lịch đã xác nhận của GameAccount.
3. Thông tin khôi phục không bao giờ được nhập vào mô tả listing hoặc chuyển cho người thuê.

## UC-05 — Kiểm duyệt tin và gỡ tin vi phạm

**Luồng chính:**
1. Hệ thống kiểm tra nội dung, hình ảnh, dấu hiệu lừa đảo, cheat, dữ liệu cá nhân và từ khóa rủi ro.
2. Admin xem hồ sơ quyền niêm yết game khi cần.
3. Tin được duyệt, từ chối hoặc yêu cầu sửa.
4. Nếu có thông báo nhà phát hành/người có quyền, admin ghi nhận nội dung, URL/tin liên quan, thời điểm, căn cứ và hạn phản hồi.
5. Admin tạm ẩn, gỡ hoặc giữ tin tùy mức độ và nghĩa vụ áp dụng; thông báo cho người đăng và lưu dấu vết quyết định.
6. Người đăng có thể khiếu nại quyết định kiểm duyệt.

**Quy tắc:** phải có cơ chế xử lý nhanh, nhưng quyết định xử lý và chứng cứ không bị xóa khỏi nhật ký nội bộ.

## UC-06 — Chọn thời lượng, giữ lịch và tạo đơn thuê

**Tiền điều kiện:** người thuê đủ 18 tuổi, `status = ACTIVE` (không `RESTRICTED`/`SUSPENDED`); listing `ACTIVE`; game `ALLOWED`.

**Luồng chính:**
1. Người thuê chọn `start_at` và thời lượng (`durationMinutes`).
2. Hệ thống kiểm tra lịch trống của GameAccount, giới hạn tài khoản, thời lượng tối thiểu/tối đa, điều kiện độ tuổi, trạng thái người thuê và trạng thái game.
3. Hệ thống tính và hiển thị **tổng thanh toán = tiền thuê + tiền cọc** (`rent_amount + deposit_amount`), kèm thời gian giữ cọc sau thuê (`deposit_hold_hours`). Nếu người thuê còn cọc đang giữ ở đơn khác, hệ thống vẫn yêu cầu cọc đầy đủ cho đơn này và hiển thị ghi chú "Cọc của mỗi đơn là riêng biệt" (UC-25).
4. Người thuê đọc và xác nhận quy tắc dùng account, các hành vi bị cấm, cửa sổ khiếu nại, chính sách hủy, điều kiện hoàn tiền và điều kiện khấu trừ cọc.
5. Hệ thống tạo đơn ở trạng thái `PENDING_PAYMENT` (kèm `idempotency_key`, `policy_snapshot` gồm giá, cọc, `end_mode` và tham số game), tạo bản ghi cọc `PENDING_PAYMENT` gắn 1–1 với đơn, và giữ chỗ 15 phút (`payment_expires_at`). Exclusion constraint trên (`game_account_id`, `rental_period`) chặn mọi đơn chồng giờ.
6. Nếu thanh toán không hoàn tất trong thời gian giữ chỗ, đơn chuyển `EXPIRED`, cọc `VOID` và lịch được mở lại.

**Ngoại lệ:** lịch bị chiếm trước khi thanh toán → yêu cầu chọn lịch khác; game bị tạm dừng niêm yết → không cho tiếp tục; người thuê `RESTRICTED` (ví dụ đang bị điều tra chiếm account theo UC-43) → không cho tạo đơn; gửi lại cùng `idempotency_key` → trả về đơn đã tạo, không tạo đơn mới.

## UC-07 — Thanh toán, xác nhận đơn và tính phí

**Luồng chính:**
1. Hệ thống tính tiền thuê (`rent_amount` = giá theo giờ × thời lượng) và lấy tiền cọc của listing (`deposit_amount`). **Người thuê trả tiền thuê + cọc**; không có phí cộng thêm cho người thuê.
2. **Phí nền tảng là hoa hồng trừ từ tiền thuê của chủ account:** `platform_fee = rent_amount × platform_fee_bps / 10000` (mặc định `platform_fee_bps = 1000`, tức 10%); `seller_net = rent_amount − platform_fee`. Phí không tính trên tiền cọc. Hai giá trị này được lưu trong `policy_snapshot` của đơn.
3. Người thuê thanh toán một lần qua PSP được tích hợp (`PaymentIntent.amount = rent_amount + deposit_amount`).
4. Hệ thống chỉ xác nhận đã trả tiền sau khi nhận webhook hợp lệ (chữ ký HMAC-SHA256), idempotent theo `provider_ref`.
5. Khi thanh toán thành công: ledger ghi `PAYMENT_CAPTURED` (tiền thuê vào `RENT_ESCROW`, cọc vào `DEPOSIT_HELD`); cọc chuyển `HELD`; đơn chuyển `CONFIRMED` nếu listing bật `AUTO_ACCEPT`, ngược lại chuyển `AWAITING_OWNER_APPROVAL` (chủ account duyệt trong 30 phút; từ chối hoặc quá hạn → `REJECTED`, hoàn 100% tiền thuê + cọc). Lịch không còn cho người khác thuê trong khung giờ đó.
6. Hệ thống ghi rõ tiền thuê, phí nền tảng, `seller_net`, tiền cọc, khoản hoàn/điều chỉnh và số tiền dự kiến chi trả cho chủ account. Phí và `seller_net` chỉ ghi nhận thành doanh thu khi đơn `ENDED` (bút toán `ORDER_ENDED`).

**Ngoại lệ:** webhook sai chữ ký → từ chối, không đổi trạng thái; webhook trùng → bỏ qua; thanh toán sau khi đơn đã `EXPIRED` → hoàn tiền tự động, không khôi phục đơn.

**Lưu ý:** không mặc định triển khai ví nạp tiền hoặc giữ tiền dài hạn. Cách giữ/chi trả dòng tiền phải phù hợp hợp đồng PSP và quy định Việt Nam. Dùng PSP hỗ trợ marketplace/split settlement nếu phù hợp thay vì tự tạo dịch vụ giữ hộ tiền mà chưa thẩm định.

## UC-08 — Bàn giao quyền truy cập

**Mục tiêu:** bàn giao credentials trực tiếp cho người thuê sau khi đơn hợp lệ — không qua client/launcher — với phạm vi xem hẹp nhất, có dấu thời gian và audit, không lộ thông tin khôi phục.

**Tác nhân:** Người thuê, chủ account, hệ thống RentGG.

**Tiền điều kiện:**
- Đơn ở `IN_RENTAL` (đã thanh toán tiền thuê + cọc, đã `CONFIRMED`, đã đến `start_at` và GameAccount `AVAILABLE` → `IN_USE`).
- GameAccount có một `CredentialVersion` `ACTIVE` (mã hóa AES-256-GCM, có `key_id`).
- Game được cấu hình một chế độ bàn giao (`handover_mode`):
  - `STATIC_CREDENTIAL`: username + mật khẩu đủ để đăng nhập, không cần mã xác thực mỗi lần.
  - `OWNER_ASSISTED_OTP`: đăng nhập cần mã OTP/xác nhận gửi tới thiết bị hoặc email của chủ account; chủ account phải trực tiếp hỗ trợ.

**Luồng chính:**
1. Đến `start_at`, job `startDueOrders` chuyển đơn `CONFIRMED` → `IN_RENTAL`, GameAccount `AVAILABLE` → `IN_USE`; hệ thống thông báo cho hai bên rằng credentials đã sẵn sàng.
2. Người thuê mở chi tiết đơn và chọn "Xem thông tin đăng nhập". Hệ thống kiểm tra: người gọi đúng là `renter_id` của đơn; đơn ở `IN_RENTAL`; thời điểm hiện tại nằm trong [`start_at`, `block_end_at`]; phiên đăng nhập RentGG hợp lệ; chưa vượt giới hạn tần suất (10 lần/giờ/đơn).
3. Hệ thống giải mã `CredentialVersion` `ACTIVE` và trả username + mật khẩu cho người thuê; không trả email/SĐT khôi phục hoặc mã 2FA.
4. Mỗi lần xem tạo một bản ghi audit (đơn, người xem, thời điểm, phiên bản credential, IP/thiết bị ở mức tối thiểu); không ghi mật khẩu vào log.
5. **Lần xem đầu tiên** ghi `credential_first_viewed_at` và **bắt đầu complaint window** (`complaint_window_minutes`, mặc định 15 phút) để người thuê báo lỗi đăng nhập/sai mô tả (UC-10, UC-33).
6. Nếu game là `OWNER_ASSISTED_OTP`: người thuê bấm "Yêu cầu mã"; chủ account nhận thông báo, **tự lấy mã từ thiết bị/email của mình và đưa cho người thuê** qua kênh của nền tảng (MVP: ghi chú trong đơn); RentGG không lưu OTP lâu dài, không tự động đọc hoặc chuyển tiếp mã, không vượt qua MFA của nhà phát hành.
7. Người thuê xác nhận đã đăng nhập đúng account (`handover_confirmed_at`) hoặc mở khiếu nại `RENTER_LOGIN_FAILED`/`RENTER_NOT_AS_DESCRIBED` trong complaint window.

**Ngoại lệ:**
- Người không phải renter của đơn, hoặc ngoài cửa sổ [`start_at`, `block_end_at`], hoặc đơn không ở `IN_RENTAL` → từ chối, ghi audit lần thử.
- Vượt giới hạn tần suất → tạm khóa xem, thông báo người thuê và ghi tín hiệu rủi ro.
- `OWNER_ASSISTED_OTP` mà chủ account không đưa mã trong thời gian hợp lý → người thuê mở `RENTER_LOGIN_FAILED`; nếu xác minh lỗi do chủ account → hoàn tiền theo UC-33 và ghi tín hiệu lạm dụng chủ account (UC-14).
- Mật khẩu sai/không đăng nhập được → khiếu nại trong complaint window theo UC-10.

**Quy tắc nghiệp vụ:**
1. Chỉ renter của đơn được xem credentials; nhân viên hỗ trợ/admin/Finance **không có chức năng xem mật khẩu** ở cả giao diện lẫn API.
2. Credentials chỉ xem được trong [`start_at`, `block_end_at`]; sau `block_end_at` hoặc khi đơn kết thúc sớm (UC-43), quyền xem chấm dứt ngay.
3. Không lưu plaintext; DTO credentials không in mật khẩu khi log; mã hóa có `key_id` để xoay khóa.
4. Thông tin khôi phục (email, SĐT, 2FA) luôn do chủ account giữ (UC-44), không bao giờ là một phần của bàn giao.
5. OTP do chủ account tự đưa; RentGG không vượt MFA, không lưu OTP lâu dài.
6. `AUTO_ACCEPT` không được bật với game `OWNER_ASSISTED_OTP` vì bàn giao cần chủ account có mặt (UC-38).
7. Sau khi đơn kết thúc, credential đã bàn giao coi như đã lộ và phải rotation trước đơn kế tiếp (UC-39).

**Can thiệp thủ công:** hỗ trợ có thể tạm chặn xem credentials của một đơn khi có nghi ngờ bảo mật (UC-43) hoặc yêu cầu chủ account hỗ trợ OTP; không ai được xem mật khẩu thay người thuê. Mọi thao tác ghi audit theo UC-42.

**Tiêu chí nghiệm thu:**
- Chỉ renter của đơn, trong [`start_at`, `block_end_at`] và đơn `IN_RENTAL`, xem được credentials; mọi trường hợp khác trả lỗi và có audit.
- Lần xem đầu ghi `credential_first_viewed_at` đúng một lần và complaint window tính từ thời điểm đó.
- Mỗi lần xem có một bản ghi audit; log ứng dụng không chứa mật khẩu.
- Không tồn tại endpoint cho Support/Admin xem mật khẩu.
- Với `OWNER_ASSISTED_OTP`, yêu cầu mã tạo thông báo cho chủ account và OTP không được lưu lâu dài.
- Không thể bật `AUTO_ACCEPT` cho listing thuộc game `OWNER_ASSISTED_OTP`.

## UC-09 — Theo dõi thuê, kết thúc và trả account

**Luồng chính:**
1. Hệ thống nhắc thời điểm bắt đầu/kết thúc.
2. Trong thời gian thuê, người thuê chỉ sử dụng theo giới hạn đã xác nhận.
3. Trước khi hết hạn, hệ thống gửi thông báo gia hạn nếu chức năng được bật.
4. Đến `block_end_at`, đơn chuyển sang `ENDED` (ghi `actual_end_at`); quyền xem credentials chấm dứt; GameAccount chuyển `ROTATION_PENDING` (hoặc `AWAITING_RECLAIM` nếu `STAY_ONLINE_UNTIL_RECLAIM`) theo UC-37/UC-39; người thuê được hướng dẫn đăng xuất.
5. Doanh thu chủ account vào hold review (UC-41); cọc chuyển `POST_RENTAL_HOLD` với `hold_until = actual_end_at + deposit_hold_hours` (UC-25).
6. Nếu có sự cố, mở Dispute gắn với đơn (trạng thái đơn vẫn là `ENDED`); phần tiền liên quan chuyển sang dispute hold và/hoặc cọc chuyển `DISPUTED`, xử lý theo UC-13/UC-27.

**Ngoại lệ:** không thể đăng xuất/không thể bàn giao lại; tài khoản bị chủ sở hữu truy cập trong khi thuê; phát hiện sự cố bảo mật → mở khiếu nại khẩn cấp; người thuê đổi mật khẩu/thông tin khôi phục → UC-43.

## UC-10 — Báo lỗi đăng nhập, sai mô tả hoặc nhầm account

**Loại lỗi:** sai mật khẩu/không truy cập được; account khác mô tả; sai server/region; account bị chiếm phiên; chủ account truy cập làm gián đoạn; lỗi do thiết bị/mạng người thuê.

**Luồng chính:**
1. Người thuê chọn lý do cụ thể, nhập mô tả và gửi bằng chứng.
2. Hệ thống đóng dấu thời gian và áp dụng cửa sổ báo lỗi đã được hiển thị trước checkout.
3. Khi cần, tạm dừng đồng hồ thuê trong lúc kiểm tra theo chính sách của loại lỗi.
4. Hỗ trợ kiểm tra lịch sử bàn giao, ảnh, log đơn và phản hồi của chủ account.
5. Kết quả có thể là: hủy đơn và hoàn tiền theo phần thời gian được xác định; tiếp tục đơn; cấp hỗ trợ kỹ thuật; hoặc bác khiếu nại có căn cứ.
6. Ghi lý do và cho phép khiếu nại lại theo quy tắc giới hạn.

**Quy tắc:** không áp dụng máy móc một cửa sổ thời gian cho mọi lỗi; hiển thị quy tắc trước khi người dùng trả tiền. Tránh phạt người dùng chỉ vì khiếu nại thất bại nếu không có bằng chứng họ cố tình gian lận.

## UC-11 — Chủ account báo người thuê phá hoại

**Tình huống:** dùng cheat; cố ý bỏ trận/phá rank; tiêu hao hoặc phân rã tài sản; quảng cáo/spam; xóa bạn; đổi thông tin; thay đổi cấu hình gây hại; tiếp tục truy cập sau khi hết hạn.

**Luồng chính:**
1. Chủ account mở đúng đơn thuê và chọn loại vi phạm.
2. Hệ thống yêu cầu chứng cứ phù hợp: ảnh trước/sau, thông báo nhà phát hành, lịch sử trận, dữ liệu đơn và các bằng chứng khác.
3. Nếu nguy cơ còn tiếp diễn, hỗ trợ có thể chặn xem credentials của đơn và yêu cầu chủ account đổi mật khẩu (cơ chế kết thúc phiên duy nhất vì không có client) theo điều khoản đã công bố. Nếu người thuê đã đổi mật khẩu/thông tin khôi phục, chuyển sang UC-43.
4. Người thuê được thông báo cáo buộc và có cơ hội phản hồi, trừ biện pháp khẩn cấp cần thiết để bảo vệ account.
5. Hỗ trợ phân loại: vi phạm đã chứng minh / chưa đủ chứng cứ / nguyên nhân bên thứ ba / tranh chấp ToS của chủ account.
6. Áp dụng biện pháp trong quy định: cảnh báo, hủy phiên, điều chỉnh thanh toán, hạn chế tài khoản hoặc yêu cầu bồi thường theo căn cứ hợp lệ.
7. Lưu quyết định, chứng cứ và lịch sử kháng nghị.

**Quan trọng:** UC này liên kết với UC-25 và UC-27. Mọi listing đều có cọc bắt buộc; chủ account phải mở khiếu nại `OWNER_DAMAGE` trước khi hết thời gian giữ cọc (`deposit_hold_hours`, mặc định 72 giờ sau khi đơn kết thúc). Chỉ khấu trừ cọc của chính đơn đó sau khi xác minh vi phạm theo chính sách đã chấp nhận, cung cấp lý do và mở quyền phản hồi/kháng nghị. Không được tự ý giữ tiền vô thời hạn hoặc dùng cọc của đơn khác/người dùng khác để thanh toán. Nếu không đủ tiền bảo đảm, phần thiệt hại vượt quá khoản có thể được xử lý bằng yêu cầu bồi thường theo căn cứ hợp lệ; không hứa chắc chắn thu hồi được tiền.

**Liên kết:** các báo cáo của chủ account bị bác hoặc xác định là sai được ghi nhận thành tín hiệu trong chỉ số lạm dụng của chủ account (UC-14); chỉ tính khi đã được xác minh qua phân xử.

## UC-12 — Phát hiện cheat / hành vi nguy hiểm

**Luồng chính:**
1. Hệ thống nhận báo cáo của chủ account/người dùng, thông báo của nhà phát hành (ví dụ account bị phạt vì cheat) hoặc chứng cứ hỗ trợ.
2. Báo cáo/tín hiệu chỉ là căn cứ để xem xét, không mặc nhiên kết luận vi phạm.
3. RentGG không có client/launcher và không cài phần mềm giám sát trên thiết bị người thuê; bằng chứng dựa trên dữ liệu đơn, audit xem credentials, ảnh trước/sau, lịch sử trận do game cung cấp và thông báo chính thức của nhà phát hành.
4. Khi rủi ro cao trong lúc thuê, hỗ trợ có thể chặn xem credentials và yêu cầu chủ account đổi mật khẩu để kết thúc phiên; tạo hồ sơ để xem xét. Thiệt hại xác minh được xử lý qua khấu trừ cọc (UC-27).
5. Người dùng có quyền gửi kháng nghị nếu phát hiện nhầm.

**MVP:** chưa tự phát triển công cụ phát hiện cheat. Bắt đầu bằng báo cáo, chứng cứ, quy tắc cấm, hỗ trợ thủ công và hợp tác với nhà cung cấp công cụ hợp pháp nếu có. Không tìm cách né anti-cheat hoặc che giấu việc thuê khỏi nhà phát hành.

## UC-13 — Phân xử tranh chấp

**Luồng chính:**
1. Hệ thống tạo hồ sơ tranh chấp liên kết người dùng, tin, đơn, thanh toán, lịch sử chat và chứng cứ.
2. Xác định nhóm: lỗi truy cập; sai mô tả; người thuê vi phạm; chủ account vi phạm; account bị khóa bởi nhà phát hành; gian lận thanh toán; hoặc nguyên nhân chưa rõ.
3. Hệ thống có thể áp dụng tạm dừng xử lý thanh toán liên quan nếu được hợp đồng PSP và quy định áp dụng cho phép; ghi rõ lý do.
4. Hỗ trợ yêu cầu thêm bằng chứng và cho hai bên thời hạn phản hồi.
5. Người xử lý độc lập với bên có lợi ích trực tiếp xem xét bằng chứng theo chính sách đã công bố.
6. Kết quả: hoàn toàn phần/toàn bộ trong phạm vi quy định; thanh toán theo thời gian sử dụng; không hoàn; xử lý tài khoản; yêu cầu hai bên giải quyết bồi thường ngoài nền tảng; hoặc chuyển cơ quan có thẩm quyền khi cần.
7. Hai bên nhận thông báo lý do và cách kháng nghị.

**Quy tắc:** tiêu chí và mức xử lý phải công khai; lưu lịch sử chỉnh sửa; không tự động coi lời nói của chủ account hay người thuê là đúng nếu thiếu bằng chứng.

## UC-14 — Điểm uy tín và kiểm soát tái phạm

**Tín hiệu có thể dùng:** đơn hoàn thành; khiếu nại được xác minh; hủy đơn có lỗi; không bàn giao; báo cáo sai; vi phạm quy tắc; account bị khóa do cheat có bằng chứng; thời gian phản hồi; kết quả kháng nghị.

**Cách áp dụng:** nhắc nhở → yêu cầu xác minh tăng cường → giới hạn số đơn/giá trị đơn → tạm đình chỉ → khóa vĩnh viễn với vi phạm nghiêm trọng đã chứng minh.

Không dùng duy nhất tỷ lệ khiếu nại hoặc tỷ lệ hủy làm căn cứ tự động khóa; cần tính số lượng mẫu, loại lý do, lỗi hệ thống và kết quả phân xử.

**Chỉ số lạm dụng của chủ account (bổ sung):**

*Mục tiêu:* phát hiện chủ account lạm dụng cơ chế bảo vệ, ví dụ lấy lại hoặc truy cập account khi đơn đang chạy, đổi mật khẩu ngoài luồng, báo cáo sai người thuê, hoặc khai "account bị chiếm" sau thời gian hold để né nghĩa vụ; đồng thời không phạt oan chủ account trung thực.

*Tín hiệu (mỗi tín hiệu gắn `order_id`, nguồn dữ liệu và trạng thái xác minh):*
1. Đăng nhập, truy cập hoặc đổi thông tin account trong lúc đơn ở `IN_RENTAL`, theo nguồn dữ liệu được phép (báo cáo người thuê kèm bằng chứng, thông báo đăng nhập/đổi mật khẩu do game gửi, audit bàn giao và rotation của RentGG).
2. Đổi mật khẩu hoặc thu hồi account ngoài luồng hệ thống (UC-39, UC-40) khi đơn còn hiệu lực.
3. Báo cáo người thuê vi phạm bị bác hoặc xác định là sai (UC-11, UC-33).
4. Khiếu nại "account bị chiếm/hack" sau hold mà điều tra cho thấy nguyên nhân từ chính chủ account (lấy lại account).
5. Hủy sát giờ lặp lại (UC-40) hoặc không bàn giao (UC-08).
6. Mô tả listing lệch đáng kể so với trạng thái thực tế (UC-30, UC-35).
7. Không hoàn tất rotation đúng hạn: GameAccount rơi vào `ROTATION_OVERDUE`, hoặc đơn kế tiếp bị hủy với `cancel_reason = OWNER_FAULT_NOT_READY` vì account chưa `AVAILABLE` khi đến giờ bắt đầu (UC-39). Tín hiệu `OWNER_FAULT_NOT_READY` được coi là đã xác minh bằng log hệ thống.
8. Không duy trì được quyền kiểm soát thông tin khôi phục đã cam kết (UC-44), ví dụ không lấy lại được account sau sự cố vì đã mất email/SĐT/2FA.

*Chỉ số:* tỷ lệ tín hiệu đã xác minh trên số đơn hoàn tất trong cửa sổ N ngày (cấu hình theo game/risk tier), có ngưỡng mẫu tối thiểu. Tín hiệu chưa xác minh chỉ dùng để xếp hàng xem xét, không dùng làm căn cứ chế tài.

*Mức xử lý theo bậc:* nhắc nhở → xác minh tăng cường → giảm xếp hạng hoặc tắt `AUTO_ACCEPT` (UC-38) → nâng `risk_tier` và kéo dài hold payout (UC-41) → giới hạn số listing → đình chỉ hoặc khóa vĩnh viễn khi lặp lại và có chứng cứ.

*Quy tắc:*
1. Chỉ tín hiệu đã được xác minh bằng log kỹ thuật hoặc kết quả phân xử mới được tính vào chế tài; không tự động khóa chỉ dựa trên tỷ lệ.
2. Tách bạch với trường hợp account bị nhà phát hành khóa (UC-18); không suy diễn lạm dụng từ thời điểm bị khóa.
3. Chủ account xem được chỉ số của mình, lý do và cách kháng nghị; tín hiệu bị gỡ khi kháng nghị thành công.
4. Chỉ dùng dữ liệu đã công bố trong điều khoản; không thu thập thêm để chấm điểm.
5. Can thiệp thủ công theo UC-42; đình chỉ hoặc khóa vĩnh viễn cần hai người duyệt.
6. Mọi thay đổi bậc xử lý ghi audit log (người/hệ thống, lý do, tín hiệu liên quan, thời điểm).

*Tiêu chí nghiệm thu:*
- Mỗi tín hiệu truy vết được về đơn, nguồn dữ liệu và trạng thái xác minh.
- Tín hiệu chưa xác minh không kích hoạt chế tài tài chính hoặc đình chỉ.
- Chủ account kháng nghị thành công thì chỉ số được tính lại.
- Việc nâng `risk_tier` được phản ánh trong hold của UC-41 và trạng thái `AUTO_ACCEPT` của UC-38.
- Có kiểm thử ngưỡng mẫu nhỏ, tín hiệu trùng lặp và trường hợp account bị nhà phát hành khóa không do chủ account.

## UC-15 — Chi trả doanh thu cho chủ account

**Luồng chính:**
1. Khi đơn kết thúc và hết cửa sổ khiếu nại, hệ thống tính tiền thuê trừ phí nền tảng, hoàn tiền và điều chỉnh hợp lệ.
2. Chủ account khai báo phương thức nhận tiền và hoàn thành xác minh cần thiết.
3. Hệ thống tạo yêu cầu chi trả qua PSP/đối tác thanh toán.
4. Ghi trạng thái lệnh rút `REQUESTED`, `PAID` hoặc `FAILED` (MVP: Finance chi thủ công theo lô và đánh dấu kết quả; `FAILED` đảo tiền về số dư khả dụng).
5. Khi chi trả thất bại, thông báo lý do và cho phép cập nhật phương thức nhận tiền.

**Không đưa vào mặc định:** tự tạo ví điện tử hoặc nắm giữ tiền của người dùng vô thời hạn khi chưa có đánh giá pháp lý và thỏa thuận PSP phù hợp.

## UC-16 — Cấp merchant/shop

**Ý tưởng từ mô hình Trung Quốc:** ngoài người dùng thường, có nhóm người bán/merchant được xác minh, trang quản lý hàng loạt, thống kê và giới hạn đăng tin cao hơn.

**Luồng chính:** chủ account nộp hồ sơ → admin kiểm tra danh tính/hồ sơ hoạt động → cấp trạng thái merchant → mở công cụ bổ sung phù hợp → theo dõi chất lượng và tái thẩm định.

**Chưa sao chép nguyên trạng:** không lấy nguyên mức phí hoặc giới hạn đăng tin của Zuhaowan từ tài liệu cũ; cấu hình cần dựa trên chi phí và nhu cầu RentGG.

## UC-17 — Đặt lịch trước, gia hạn và hủy đơn

**Mục tiêu:** cho người thuê đặt trước khung giờ, giúp chủ account quản lý lịch khả dụng và quy định công bằng về gia hạn/hủy.

**Tác nhân:** Người thuê, chủ account, hệ thống RentGG, PSP, hỗ trợ.

**Luồng đặt lịch:**
1. Người thuê chọn listing, ngày/giờ bắt đầu, thời lượng và gói thuê.
2. Hệ thống kiểm tra listing đang hoạt động, game được phép niêm yết, chủ account đủ điều kiện, account không có đơn chồng lấn và không trong trạng thái đổi mật khẩu/kiểm tra bảo mật.
3. Hệ thống hiển thị tổng thanh toán (tiền thuê + tiền cọc bắt buộc), thời gian giữ cọc sau thuê, chính sách hủy của người thuê và chính sách chủ account hủy lịch, gồm hạn chót hủy miễn chế tài.
4. Người thuê xác nhận điều kiện và thanh toán/ủy quyền thanh toán theo tích hợp PSP.
5. Hệ thống khóa khung thời gian theo cơ chế chống đặt đồng thời. Nếu listing bật `AUTO_ACCEPT`, hệ thống tự xác nhận khi đủ điều kiện; nếu bật `MANUAL_APPROVAL`, đơn chờ chủ account xác nhận trong thời hạn quy định.
6. Sau khi đơn được xác nhận, khung giờ trở thành lịch đã cam kết. Chủ account có thể tạm dừng nhận đơn mới nhưng việc tạm dừng không xóa hoặc hủy các lịch đã xác nhận.
7. Hệ thống gửi xác nhận cho hai bên và nhắc lịch trước giờ bắt đầu.

**Luồng gia hạn (P1 — chưa có trong MVP):**
1. Người thuê gửi yêu cầu gia hạn hoặc chọn gia hạn tự động nếu listing hỗ trợ.
2. Hệ thống kiểm tra lịch tiếp theo chưa có đơn/khóa bảo trì.
3. Hiển thị giá và thời gian mới; gia hạn chỉ có hiệu lực sau thanh toán thành công.
4. Hệ thống cập nhật `scheduled_end_at`, phát thông báo cho chủ account và ngăn lịch chồng lấn.

**Luồng người thuê hủy:**
1. Người thuê chọn hủy và xem trước số tiền được hoàn cùng tiền đặt cọc được giải phóng.
2. Hệ thống áp dụng chính sách theo thời điểm hủy, thời điểm bắt đầu, lý do và tình trạng bàn giao: hủy trước `start_at` ít nhất `renter_free_cancel_minutes` (mặc định 120 phút) → hoàn 100% tiền thuê; muộn hơn → hoàn `late_cancel_refund_bps` (mặc định 50%) tiền thuê, phần còn lại thành doanh thu chủ account. **Cọc luôn hoàn 100% ngay khi hủy trước `start_at`, không giữ thêm.** Không hủy được sau khi đơn đã `IN_RENTAL` (dùng khiếu nại).
3. Nếu hủy do listing sai, chủ account không bàn giao hoặc lỗi hệ thống/PSP, xử lý theo luồng lỗi tương ứng chứ không tính như người thuê đổi ý.
4. Hệ thống ghi lại lý do, thời điểm và kết quả hoàn tiền.

**Ngoại lệ:**
- Lịch đã hết khả dụng do giao dịch đồng thời: từ chối đặt và giải phóng khoản thanh toán chưa capture.
- Chủ account tắt `AUTO_ACCEPT` sau khi đơn đã xác nhận: chỉ ảnh hưởng đơn mới, không tự hủy đơn đã xác nhận.
- Có khiếu nại ToS/quyền niêm yết hoặc vấn đề bảo mật trước giờ bắt đầu: tạm khóa việc bàn giao, chuyển hỗ trợ rà soát và thông báo cho người thuê.
- Không thể đáp ứng lịch vì lỗi của chủ account: dùng UC-40, hoàn tiền cho người thuê và áp dụng chế tài theo thời điểm/lý do.
- Trường hợp bất khả kháng hoặc sự cố nhà phát hành diện rộng: hỗ trợ đánh giá chứng cứ, không tự động phạt nếu chưa xác định lỗi.

**Quy tắc nghiệp vụ:**
1. Lịch xác nhận là cam kết dịch vụ; chủ account không được nhận đơn khác trùng giờ hoặc xóa lịch đã đặt chỉ bằng cách tạm dừng listing.
2. Chính sách hủy phải hiện trước khi thanh toán, có phiên bản lưu cùng đơn.
3. Mọi khoản hoàn tiền/cọc được giải phóng phải gắn với `order_id` và có ledger/audit log.
4. Không áp dụng một mức hoàn tiền chung cho tất cả tình huống; tách người thuê đổi ý, lỗi chủ account, lỗi hệ thống và bất khả kháng.
5. Việc gia hạn phải được xác nhận bởi hệ thống trước thời điểm kết thúc và không được chiếm khung giờ đã bán cho người khác.
6. Đơn hủy luôn có trạng thái `CANCELLED` kèm `cancel_reason` phân biệt người/lý do hủy, ví dụ `RENTER_CANCEL`, `OWNER_CANCEL`, `OWNER_FAULT_NOT_READY`, `PLATFORM_CANCEL`, `FORCE_MAJEURE` (danh sách đầy đủ ở Phụ lục).
7. Chính sách hủy của chủ account được đặc tả riêng trong UC-40; quy tắc mặc định đề xuất là được hủy không chịu chế tài nếu báo trước ít nhất 12 giờ, với ngưỡng có thể cấu hình theo game/listing và loại lịch.

**Tiêu chí nghiệm thu:** không tạo lịch trùng; chính sách hủy hiển thị trước checkout; trạng thái người hủy và lý do được ghi nhận; tiền hoàn/cọc được giải phóng đúng quy tắc; việc chủ account dừng nhận đơn mới không làm mất lịch đã xác nhận.

## UC-18 — Khiếu nại account bị khóa / gói bảo vệ account

**Từ mô hình Trung Quốc:** Zuhaowan có sản phẩm bảo vệ liên quan đến account bị khóa với điều kiện đủ cụ thể, bằng chứng nhà phát hành, lịch sử đơn hợp lệ, giới hạn thời gian và hạn mức tiền. Đây không phải bảo đảm hoàn toàn giá trị account; ví dụ quy tắc công khai tại nguồn tham khảo giới hạn bồi thường theo thu nhập thuê trung bình, số giờ/ngày và tối đa 30 ngày.

**Cách thiết kế cho RentGG:**
1. Chủ account báo sự cố và cung cấp thông báo chính thức của nhà phát hành nếu có.
2. RentGG kiểm tra nguyên nhân, thời điểm, đơn liên quan và dữ liệu trước/sau.
3. Phân biệt: có chứng cứ người thuê gây khóa; nhà phát hành khóa do việc chia sẻ/cho thuê; account vi phạm từ trước; hoặc nguyên nhân chưa rõ.
4. Chỉ được quảng bá gói bảo vệ/hoàn tiền khi có điều khoản, nguồn tiền hoặc đối tác bảo hiểm hợp lệ, tiêu chí loại trừ và cách tính minh bạch.
5. Nếu không có đối tác/quỹ, gọi đây là quy trình tranh chấp, không quảng cáo như “bảo hiểm”.

**Trạng thái mục tiêu:** Có đầy đủ luồng nghiệp vụ trong mô hình; phát hành gói tài chính chỉ sau khi xác lập nguồn chi trả, đối tác và rà soát pháp lý.

## UC-19 — Đồng bộ nhiều kênh (mở rộng)

Mỗi account chỉ được có một lịch thuê được xác nhận tại một thời điểm. Nếu một listing xuất hiện trên nhiều kênh, hệ thống cần kho inventory trung tâm, khóa lịch nhất quán và xử lý đồng thời để không bán chồng chéo. Đây là tính năng nâng cao, không cần cho MVP.

## UC-20 — Yêu cầu từ nhà phát hành và phản ứng pháp lý

1. Ghi nhận thông báo: người gửi, thẩm quyền/quan hệ quyền, game, URL/tin, nội dung yêu cầu và thời hạn.
2. Đánh giá khẩn cấp theo chính sách nội bộ; tạm ẩn giao dịch khi có căn cứ và cần ngăn thiệt hại tiếp diễn.
3. Kiểm tra ToS, quyền hình ảnh/thương hiệu, quyền sở hữu trí tuệ, nội dung và phạm vi yêu cầu.
4. Quyết định gỡ, sửa, giới hạn hoặc phản hồi; không xóa log.
5. Thông báo cho người đăng khi phù hợp và cho phép phản hồi/kháng nghị nếu không bị cấm.
6. Xem xét gỡ chặn nếu có căn cứ mới hoặc chấp thuận hợp lệ.

## UC-21 — An toàn người chưa thành niên và kiểm soát độ tuổi

1. Xác định tuổi/điều kiện sử dụng phù hợp với tính năng và pháp luật áp dụng.
2. Không mở tính năng cho đối tượng không đáp ứng điều kiện.
3. Không cho tin quảng bá dịch vụ là cách né xác thực, giới hạn giờ chơi hoặc quy tắc bảo vệ người chưa thành niên.
4. Có quy trình xử lý báo cáo và yêu cầu của cơ quan có thẩm quyền.
5. Giảm tối đa dữ liệu trẻ em được xử lý và áp dụng biện pháp bảo vệ riêng nếu có phát sinh.

## UC-22 — Trung tâm hỗ trợ và nhật ký kiểm toán

- Người dùng xem trạng thái đơn, thời gian thuê, thanh toán, khiếu nại và lịch sử quyết định.
- Nhân viên hỗ trợ chỉ xem dữ liệu cần thiết theo vai trò.
- Admin có thể tra cứu hành động quan trọng: thay đổi giá, sửa listing, tiết lộ quyền truy cập, hoàn tiền, gỡ tin, khóa tài khoản.
- Nhật ký phải có mốc thời gian, người thực hiện, hành động, lý do và mã đơn liên quan.
- Có chính sách lưu/xóa dữ liệu và xử lý yêu cầu dữ liệu theo pháp luật áp dụng.

## UC-23 — Cấu hình phí, game và chính sách

Admin cấu hình phí nền tảng, điều kiện thời lượng, khung giá nếu áp dụng, cửa sổ khiếu nại, luật riêng của game, giới hạn account, hạng merchant và mức phạt. Mọi thay đổi quan trọng cần phiên bản, thời điểm hiệu lực và cơ chế thông báo. Không thay đổi hồi tố gây bất lợi cho đơn đã xác nhận, trừ khi có căn cứ phù hợp.

## UC-24 — Số dư, nạp/rút tiền và ledger giao dịch

Mô hình mục tiêu có sổ cái phân biệt: số dư người dùng khả dụng; số dư bị giữ; tiền thuê đơn đang diễn ra; tiền đặt cọc; quỹ bồi hoàn lỗi listing; coupon/red packet; tiền chủ account được nhận; tiền payout đang xử lý; tiền hoàn/chargeback; phí nền tảng.

**Luồng chính:** PSP xác nhận giao dịch → ledger ghi bút toán không sửa/xóa lịch sử → tiền được phân bổ theo đúng loại → khi kết thúc đơn/qua cửa sổ tranh chấp, hệ thống ghi bút toán payout/hoàn/khấu trừ → đối soát với PSP → xử lý chênh lệch qua quy trình có nhật ký.

**Quy tắc:** không cộng trừ trực tiếp số dư bằng cách ghi đè; mọi điều chỉnh phải là bút toán bù có lý do và người phê duyệt. Phân biệt rõ tiền của người dùng với doanh thu của RentGG. Chỉ mở nạp/rút/số dư lưu trữ khi phương án PSP, quy định thanh toán, kế toán, hoàn tiền và nghĩa vụ liên quan đã được thẩm định; nếu chưa, dùng thanh toán theo đơn thông qua PSP.

---

## UC-25 — Tiền đặt cọc bắt buộc của người thuê: thiết lập, thu, giữ sau thuê và hoàn

**Mục tiêu:** mô phỏng khoản 押金 của nền tảng Trung Quốc để răn đe người thuê khỏi phá rank, sử dụng cheat, tiêu hao vật phẩm, chiếm account hoặc vi phạm giới hạn của chủ account; đồng thời bảo đảm cọc là tiền của người thuê, được giữ có thời hạn, gắn với đúng một đơn và được hoàn minh bạch. Theo R3/R4/R5, cọc là **bắt buộc với mọi listing**.

**Tác nhân:** Chủ account, người thuê, PSP, hệ thống ledger, bộ phận phân xử, Finance.

**Tiền điều kiện:**
- Game được cấu hình `deposit_min`, `deposit_max` (> 0) và `deposit_hold_hours` (mặc định 72).
- Listing có `deposit_amount` > 0 nằm trong [`deposit_min`, `deposit_max`] của game.
- Điều kiện khấu trừ cọc và thời gian giữ cọc được hiển thị trên trang listing và checkout.

**Trạng thái cọc (Deposit.status):**
| Trạng thái | Ý nghĩa |
|---|---|
| `PENDING_PAYMENT` | Đơn vừa tạo, chờ thanh toán tiền thuê + cọc. |
| `HELD` | Đã thanh toán; đơn chưa bắt đầu hoặc đang `IN_RENTAL`. |
| `POST_RENTAL_HOLD` | Đơn đã `ENDED`; cọc được giữ đến `hold_until = actual_end_at + deposit_hold_hours`. |
| `DISPUTED` | Có khiếu nại nhắm vào cọc (`OWNER_DAMAGE`, `ACCOUNT_TAKEOVER`) đang chờ phân xử. |
| `REFUND_PENDING` | Đã quyết định hoàn (toàn bộ hoặc phần còn lại), đang chờ PSP xử lý. |
| `REFUNDED` | PSP xác nhận đã hoàn cho người thuê. |
| `SETTLED` | Phân xử đã khấu trừ toàn bộ cọc cho chủ account (không còn phần hoàn). Nếu khấu trừ một phần, phần khấu trừ ghi `deducted_amount` và phần còn lại đi qua `REFUND_PENDING` → `REFUNDED`. |
| `VOID` | Đơn hết hạn thanh toán (`EXPIRED`), cọc chưa từng được thu. |

**Luồng chính:**
1. **Thiết lập:** khi tạo/sửa listing (UC-04), chủ account nhập `deposit_amount`; hệ thống từ chối mức 0 hoặc ngoài [`deposit_min`, `deposit_max`] của game.
2. **Hiển thị:** trang listing và checkout hiển thị tách biệt tiền thuê, tiền cọc, tổng thanh toán, thời gian giữ cọc sau thuê, điều kiện khấu trừ, chứng cứ cần có và quyền khiếu nại.
3. **Tạo đơn:** khi tạo đơn (UC-06), hệ thống tạo đúng một bản ghi Deposit (`order_id` duy nhất) ở `PENDING_PAYMENT` với `amount = deposit_amount` của listing tại thời điểm đặt (lưu trong `policy_snapshot`).
4. **Thu cọc:** người thuê thanh toán tiền thuê + cọc trong cùng một lần (UC-07). Khi webhook xác nhận, ledger ghi cọc vào `DEPOSIT_HELD` (nợ phải trả người thuê, không phải doanh thu RentGG hay chủ account); Deposit chuyển `HELD`.
5. **Trong lúc thuê:** cọc giữ ở `HELD`. Nếu chủ account báo chiếm account (`ACCOUNT_TAKEOVER`) trong lúc `IN_RENTAL`, cọc chuyển `DISPUTED` ngay (UC-43).
6. **Kết thúc đơn:** khi đơn `ENDED`, Deposit chuyển `POST_RENTAL_HOLD`, ghi `hold_until = actual_end_at + deposit_hold_hours`; người thuê thấy cọc và `hold_until` trong mục "Cọc của tôi".
7. **Khiếu nại trong thời gian giữ:** trước `hold_until`, chủ account có thể mở khiếu nại `OWNER_DAMAGE` (phá rank, tiêu hao vật phẩm, cheat…) hoặc `ACCOUNT_TAKEOVER` kèm bằng chứng → Deposit chuyển `DISPUTED`, job hoàn cọc tự động bỏ qua cọc này; phân xử theo UC-27.
8. **Tự hoàn khi hết hạn:** hết `hold_until` mà không có khiếu nại hợp lệ, job `releaseDeposits` chuyển Deposit `REFUND_PENDING` (bút toán `DEPOSIT_REFUND_REQUESTED`) và tạo Refund loại `DEPOSIT`; PSP xác nhận → `REFUNDED` (bút toán `REFUND_COMPLETED`).
9. **Hủy trước khi bắt đầu:** đơn bị hủy trước `start_at` (người thuê, chủ account, nền tảng) hoặc bị `REJECTED` → cọc chuyển `REFUND_PENDING` ngay, hoàn 100%, **không áp dụng thời gian giữ**.
10. **Hết hạn thanh toán:** đơn `EXPIRED` → Deposit `VOID`, không có bút toán tiền.

**Luồng bắt buộc — người thuê đang bị giữ cọc đơn A thuê tiếp account khác (đơn B):**
1. Người thuê có đơn A đã `ENDED`, cọc A đang `POST_RENTAL_HOLD` (hoặc `DISPUTED`).
2. Người thuê đặt thuê một account khác (hoặc chính account đó ở khung giờ khác) — đơn B.
3. Hệ thống tạo Deposit B mới ở `PENDING_PAYMENT` với mức cọc của listing B; tổng thanh toán đơn B = tiền thuê B + cọc B, **không trừ, không khấu trừ, không tham chiếu cọc A**. Checkout hiển thị ghi chú: "Cọc của đơn trước vẫn đang được giữ đến [hold_until A] và sẽ hoàn riêng; đơn này cần cọc riêng."
4. Người thuê thanh toán đơn B; cọc B chuyển `HELD` độc lập.
5. Cọc A tiếp tục vòng đời riêng (tự hoàn khi hết hạn hoặc theo kết quả phân xử của đơn A); kết quả của A không ảnh hưởng cọc B và ngược lại.
6. Nếu người thuê không thanh toán đủ cọc B, đơn B không được tạo hợp lệ (hết hạn → `EXPIRED`, cọc B `VOID`); cọc A không bị dùng thay.

**Ngoại lệ:**
- PSP thất bại hoặc thanh toán thiếu → đơn không được xác nhận, không bắt đầu thuê.
- Khiếu nại mở sau `hold_until` → không chặn được việc hoàn cọc đã tự động; chỉ xử lý như claim hậu kỳ nhắm vào người thuê, không lấy lại cọc đã hoàn hoặc cọc của đơn khác.
- Hoàn cọc qua PSP thất bại → Refund `FAILED`, Deposit giữ `REFUND_PENDING`, Finance đối soát và thử lại; không đánh dấu `REFUNDED` trước khi có kết quả.
- Tranh chấp quá hạn xử lý → chuyển cấp trên, không treo cọc vô thời hạn.
- Người thuê bị `RESTRICTED` (UC-43) → không tạo được đơn mới; cọc của các đơn khác của họ vẫn theo vòng đời riêng.

**Quy tắc nghiệp vụ:**
1. Mọi listing bắt buộc có cọc > 0 trong [`deposit_min`, `deposit_max`] của game; không có listing miễn cọc.
2. Cọc gắn 1–1 với đơn (`Deposit.order_id` duy nhất). **Không có "số dư cọc" dùng chung; không có chức năng/API dùng lại, gộp, chuyển cọc giữa các đơn; không bù chéo** cọc của đơn này cho thiệt hại, phí hay tiền thuê của đơn khác.
3. Cọc là tiền của người thuê (`DEPOSIT_HELD`), không phải doanh thu; chỉ chuyển cho chủ account qua bút toán `DEPOSIT_DEDUCTED` sau phân xử (UC-27).
4. Thời gian giữ sau thuê lấy từ `deposit_hold_hours` của game tại thời điểm đặt (lưu trong `policy_snapshot`); thay đổi cấu hình không hồi tố.
5. Hủy trước khi bắt đầu luôn hoàn 100% cọc ngay, không giữ.
6. Chỉ khiếu nại mở trước `hold_until` mới chặn hoàn cọc tự động.
7. Phí nền tảng không tính trên cọc.
8. RentGG phải xem xét cấu trúc thanh toán/giữ hộ tiền theo luật Việt Nam và hợp đồng PSP trước khi mở tính năng. Một "tiền cọc" ở giao diện không tự nó xác định đúng bản chất pháp lý của dòng tiền.

**Can thiệp thủ công:** hỗ trợ có thể chuyển cọc sang `DISPUTED` khi có bằng chứng dù hệ thống chưa tự kích hoạt (trước `hold_until`); Finance xử lý hoàn thất bại; mọi điều chỉnh cọc ngoài luồng phân xử chỉ qua `LEDGER_ADJUSTMENT` có hai người duyệt (UC-42). Không ai được chuyển cọc giữa các đơn.

**Tiêu chí nghiệm thu:**
- Không lưu được listing có cọc bằng 0 hoặc ngoài [`deposit_min`, `deposit_max`].
- Mỗi đơn có đúng một Deposit; tổng thanh toán luôn = tiền thuê + cọc.
- Đơn `ENDED` → cọc `POST_RENTAL_HOLD` với `hold_until` đúng bằng `actual_end_at + deposit_hold_hours`.
- Hết `hold_until` không có khiếu nại → cọc tự chuyển `REFUND_PENDING` rồi `REFUNDED`; có khiếu nại trước `hold_until` → `DISPUTED`, không bị hoàn tự động.
- Hủy trước `start_at` → hoàn 100% cọc ngay, không qua `POST_RENTAL_HOLD`; đơn `EXPIRED` → cọc `VOID`.
- **Case cọc A đang giữ, thuê đơn B:** đơn B yêu cầu thanh toán đủ cọc B; cọc A không thay đổi trạng thái, số tiền hay `hold_until`; khấu trừ/hoàn của A không ảnh hưởng B; không tồn tại API dùng lại/chuyển cọc.
- Ledger: tổng `DEPOSIT_HELD` khớp tổng cọc đang ở `HELD`/`POST_RENTAL_HOLD`/`DISPUTED`; không có bút toán cọc ghi nhận doanh thu.

## UC-26 — Quỹ bồi hoàn lỗi listing của người đăng

**Ưu tiên:** P2 (Phase 2 theo lộ trình). Không thay thế cọc bắt buộc của người thuê (UC-25).

**Mục tiêu:** mô phỏng tính năng bồi hoàn tự chọn (赔付功能) của các nền tảng Trung Quốc: người đăng có thể khóa một khoản tiền để nâng cam kết về độ chính xác của listing.

**Luồng chính:**
1. Chủ account chọn kích hoạt chương trình bồi hoàn lỗi listing và đồng ý mức phí/khoản tiền đảm bảo được công bố.
2. Hệ thống kiểm tra số dư đủ, ghi `SELLER_COMPENSATION_RESERVE` và gắn với listing theo quy tắc sản phẩm.
3. Listing được gắn nhãn “Có bảo đảm độ chính xác” chỉ khi điều kiện thực sự đáp ứng; nếu dùng việc có bảo đảm để xếp hạng cao hơn, phải công khai và không gây hiểu nhầm.
4. Khi người thuê báo sai mật khẩu hoặc mô tả lệch đáng kể trong cửa sổ cho phép, hệ thống mở dispute và kiểm tra chứng cứ.
5. Nếu lỗi được xác minh thuộc người đăng và nằm trong phạm vi bảo đảm, khoản này được chi cho người thuê theo điều khoản; đồng thời có thể hoàn tiền thuê phù hợp.
6. Nếu không phát sinh yêu cầu đủ điều kiện, khoản reserve được giải phóng theo thời hạn công bố khi gỡ tin/đóng tài khoản, trừ khi còn tranh chấp hợp lệ.

**Không dùng khoản này** để bảo đảm mọi trường hợp bị khóa bởi nhà phát hành hoặc mọi loại mất tài sản; phạm vi chỉ là các lỗi listing được nêu rõ. Mức 8 RMB từng được nêu trong tài liệu lịch sử của Zuhaowan/Zuhaoxiu là ví dụ, không phải mức RentGG mặc định.

## UC-27 — Phân xử, khấu trừ và hoàn trả tiền đặt cọc

**Mục tiêu:** quyết định khấu trừ hoặc hoàn cọc của một đơn dựa trên bằng chứng, trong thời hạn rõ ràng, ghi ledger chính xác và không bù chéo giữa các đơn.

**Tác nhân:** Chủ account (người khiếu nại), người thuê (bên bị khiếu nại), nhân viên hỗ trợ/phân xử, Finance, PSP, hệ thống ledger.

**Tiền điều kiện:**
- Đơn có Deposit ở `HELD` (chỉ với `ACCOUNT_TAKEOVER` trong lúc `IN_RENTAL`) hoặc `POST_RENTAL_HOLD` và chưa quá `hold_until`.
- Loại khiếu nại nhắm vào cọc: `OWNER_DAMAGE` hoặc `ACCOUNT_TAKEOVER`. Khiếu nại của người thuê (`RENTER_LOGIN_FAILED`, `RENTER_NOT_AS_DESCRIBED`) nhắm vào tiền thuê/doanh thu chủ account và xử lý theo UC-13/UC-33/UC-41, không khấu trừ cọc.

**Luồng chính:**
1. Chủ account mở khiếu nại trên đúng đơn, chọn loại, mô tả thiệt hại, số tiền yêu cầu và tải bằng chứng (ảnh trước/sau, lịch sử trận, thông báo nhà phát hành…).
2. Hệ thống kiểm tra thời hạn (`now < hold_until`, hoặc đơn đang `IN_RENTAL` với `ACCOUNT_TAKEOVER`), tạo Dispute `OPEN` với `deadline_at`, chuyển Deposit sang `DISPUTED` và loại khỏi job hoàn cọc tự động.
3. Hệ thống phân loại: cheat/tool trái phép; bỏ trận/phá rank; thay đổi/xóa dữ liệu; tiêu hao vật phẩm; xóa bạn bè/nhắn tin/spam; không đăng xuất/tiếp tục dùng sau giờ; đổi mật khẩu/thông tin khôi phục (UC-43); hoặc nguyên nhân chưa rõ.
4. Hệ thống thu thập nhật ký đơn, audit xem credentials, thời gian thuê, `policy_snapshot` (điều khoản và mức cọc đã chấp nhận), hình ảnh trước/sau, thông báo nhà phát hành và trao đổi liên quan.
5. Người thuê được thông báo và có thời hạn phản hồi, gửi bằng chứng bổ sung; biện pháp khẩn cấp (kết thúc đơn, chặn credentials, `RESTRICTED`) không được coi là kết luận cuối cùng.
6. Nhân viên phân xử (khác bên có lợi ích) quyết định với `reason_code`:
   - Không vi phạm / chưa đủ chứng cứ → hoàn toàn bộ cọc (`REFUND_PENDING` → `REFUNDED`), Dispute `REJECTED`.
   - Vi phạm một phần → khấu trừ `deposit_deduction` theo thiệt hại đã chứng minh (≤ số cọc), phần còn lại hoàn cho người thuê.
   - Vi phạm nghiêm trọng → khấu trừ tới toàn bộ cọc (Deposit `SETTLED`), hạn chế/khóa tài khoản người thuê; phần thiệt hại vượt cọc xử lý bằng yêu cầu bồi thường riêng.
7. Ledger ghi `DEPOSIT_DEDUCTED` (từ `DEPOSIT_HELD` sang `SELLER_AVAILABLE` của chủ account) cho phần khấu trừ và `DEPOSIT_REFUND_REQUESTED` cho phần hoàn; Dispute `RESOLVED`, lưu `decided_by`, `decided_at`.
8. Hai bên nhận thông báo số tiền, căn cứ, tài liệu sử dụng và quyền khiếu nại lại.

**Ngoại lệ:**
- Khiếu nại mở sau `hold_until` → không chặn hoàn cọc; chỉ tiếp nhận như claim hậu kỳ, không lấy lại cọc đã hoàn.
- Quá `deadline_at` chưa quyết định → chuyển cấp trên; không treo cọc vô thời hạn.
- Hoàn phần còn lại thất bại qua PSP → giữ `REFUND_PENDING`, Finance xử lý.
- Thiệt hại vượt cọc → không lấy cọc của đơn khác hay tiền của người dùng khác; chỉ ghi yêu cầu bồi thường riêng.

**Quy tắc nghiệp vụ:**
1. Chỉ khấu trừ cọc của chính đơn bị khiếu nại; **không dùng cọc của đơn khác để bù** (kể cả đơn khác của cùng người thuê đang giữ cọc), không gộp các khoản cọc.
2. Không khấu trừ chỉ vì người thuê thua trận hoặc vì account bị khóa nếu chưa xác định nguyên nhân.
3. Tổng khấu trừ không vượt số cọc của đơn; không phạt hai lần cho cùng một khoản thiệt hại.
4. Mọi quyết định có `reason_code`, người quyết định, thời điểm và bằng chứng trong audit log.
5. Khấu trừ là bút toán bất biến; điều chỉnh sau đó chỉ qua `LEDGER_ADJUSTMENT` có hai người duyệt.

**Can thiệp thủ công:** người phân xử có thể gia hạn `deadline_at` có lý do; quyết định có thể được kháng nghị một lần cho người phân xử khác; điều chỉnh tài chính sau quyết định cần hai người duyệt (UC-42).

**Tiêu chí nghiệm thu:**
- Khiếu nại `OWNER_DAMAGE` trước `hold_until` chuyển cọc `DISPUTED` và chặn hoàn tự động; sau `hold_until` thì bị từ chối mở trên cọc.
- Khấu trừ một phần tạo đúng hai bút toán (khấu trừ + hoàn phần còn lại) và tổng bằng số cọc.
- Không thể khấu trừ quá số cọc hoặc khấu trừ từ cọc của đơn khác.
- Mọi quyết định có thông báo và audit log đầy đủ.

## UC-28 — Client đăng nhập, mã mở khóa và chống cheat

**Trạng thái: Ngoài phạm vi (đã loại bỏ ở v8).** Không triển khai ở bất kỳ Phase nào trong lộ trình hiện tại.

**Lý do loại bỏ:** theo yêu cầu R1 của chủ dự án, RentGG không có Client/launcher. Launcher đăng nhập với mã mở khóa và kiểm tra môi trường đòi hỏi tích hợp sâu với từng game, rủi ro quyền riêng tư cao (giám sát tiến trình, chụp màn hình), chi phí phát triển/duy trì lớn và dễ xung đột với anti-cheat/ToS của nhà phát hành.

**Cơ chế thay thế:**
- **Bàn giao trực tiếp (UC-08):** credentials chỉ hiển thị cho người thuê của đơn trong cửa sổ [`start_at`, `block_end_at`], có audit mỗi lần xem; chế độ `STATIC_CREDENTIAL` hoặc `OWNER_ASSISTED_OTP` theo game.
- **Rotation mật khẩu (UC-39):** sau mỗi đơn, chủ account đổi mật khẩu; đây là cơ chế kết thúc phiên duy nhất (UC-37).
- **Răn đe và bồi thường:** cọc bắt buộc (UC-25, UC-27), chủ account nắm thông tin khôi phục (UC-44), luồng chiếm account khẩn cấp (UC-43), báo cáo có bằng chứng và phân xử (UC-11, UC-12, UC-13).

Mọi tham chiếu "client", "mã mở khóa", "cưỡng chế đăng xuất qua client" trong các phiên bản trước không còn hiệu lực.

## UC-29 — Gói bảo vệ account bị khóa

**Mục tiêu:** thiết kế luồng tương tự “至尊宝” của Zuhaowan, dùng để bồi thường một phần doanh thu cho thuê bị mất khi một account đủ điều kiện bị khóa.

**Luồng chính:**
1. Chủ account đăng ký sản phẩm bảo vệ cho listing/game được hỗ trợ; hệ thống lưu điều khoản, hạn mức, ngày bắt đầu hiệu lực và bên chịu chi trả.
2. Trước khi bán, giao diện hiển thị sự kiện được bảo vệ, các loại trừ, thời gian khóa tối thiểu, số đơn/giờ thuê tối thiểu, trần số tiền và hồ sơ cần nộp.
3. Nếu account bị khóa, chủ account gửi yêu cầu và tài liệu từ nhà phát hành: nguyên nhân, thời gian khóa, thời điểm phát hiện, bằng chứng account thuộc listing được bảo vệ và lịch sử cho thuê.
4. Bộ phận tiếp nhận xác định sự kiện phát sinh trong khoảng bảo vệ không, có đơn thuê hợp lệ không, và có dấu hiệu tự thuê/giao dịch giả/khai báo gian dối không.
5. Đơn vị chịu chi trả quyết định chấp thuận/từ chối/đề nghị bổ sung chứng cứ theo thời hạn công bố.
6. Khoản chi trả được tính theo hợp đồng sản phẩm, ghi vào ledger và có thể khiếu nại theo quy trình.

**Mẫu tham khảo từ trang chính thức Zuhaowan:** danh sách game có giới hạn; account phải có tối thiểu 4 đơn hợp lệ hoặc tối thiểu 8 giờ thuê; với nhiều game sự kiện khóa phải dài hơn 7 ngày; bồi thường dựa trên thu nhập cho thuê trung bình, tối đa 4 giờ/ngày, tối đa 30 ngày và không vượt số tiền bảo vệ. Các điều kiện này không được áp dụng tự động cho RentGG. Phải chốt với bên cung cấp bảo vệ/bảo hiểm và tuân thủ pháp luật Việt Nam trước khi mở bán.

## UC-30 — Kiểm tra account và bảo vệ tài sản trước khi thuê

**Luồng chính:**
1. Chủ account tự kiểm tra quyền cung cấp, mật khẩu/khả năng đăng nhập và tình trạng account trước khi đăng tin.
2. **Chủ account xác nhận mình đang nắm giữ thông tin khôi phục** (email khôi phục, số điện thoại, 2FA/xác thực) và GameAccount đã qua xác minh của admin (UC-44). Hệ thống nhắc chủ account: không chia sẻ các thông tin này với người thuê; kiểm tra rằng email/SĐT khôi phục vẫn nhận được mã; bật 2FA nếu game hỗ trợ — đây là điều kiện để lấy lại account nếu người thuê đổi mật khẩu (UC-43).
3. Hệ thống yêu cầu chụp trạng thái trước thuê ở mức đủ để đối chiếu mô tả, không để lộ thông tin khôi phục.
4. Giao diện hướng dẫn chủ account cân nhắc di chuyển tài sản có thể chuyển nhượng/tiêu hao khỏi account, nếu tính năng game cho phép và phù hợp điều khoản.
5. Chủ account khai báo các giới hạn cụ thể: không chơi xếp hạng, không tiêu hao vật phẩm, không đổi cấu hình hoặc không sử dụng tính năng nhất định; đặt mức cọc tương xứng với rủi ro trong khoảng cho phép (UC-25).
6. Người thuê đọc và xác nhận quy tắc trước khi trả tiền.
7. Khi kết thúc, chủ account kiểm tra trạng thái sau thuê trong thời gian giữ cọc (`deposit_hold_hours`) và mở khiếu nại `OWNER_DAMAGE` kèm chênh lệch trước/sau nếu có thiệt hại (UC-27).

**Quy tắc:** đây là biện pháp giảm rủi ro, không đẩy toàn bộ trách nhiệm sang chủ account và không thay thế việc phân xử có chứng cứ. GameAccount mà chủ account không còn kiểm soát thông tin khôi phục không được cho thuê (UC-44).

## UC-31 — Merchant/shop và tier

**Luồng chính:**
1. Người dùng xin trở thành merchant và nộp thông tin định danh/kinh doanh cần thiết.
2. Admin kiểm tra hồ sơ, lịch sử giao dịch, tỷ lệ lỗi listing, tỷ lệ khiếu nại và năng lực phục vụ.
3. Hệ thống gán tier, hạn mức listing, quyền quản lý nhiều tin, bảng phí/hoa hồng và quyền tự xử lý khiếu nại trong giới hạn.
4. Merchant có dashboard listing, lịch thuê, doanh thu gộp, phí, đặt cọc đang giữ, yêu cầu dispute và payout.
5. Định kỳ đánh giá lại; nếu chất lượng giảm hoặc có bằng chứng lạm dụng, giảm tier/hạn mức hoặc đình chỉ.

**Mô hình Trung Quốc công khai từng có:** người dùng thường và merchant có giới hạn đăng tin/phí/quyền khác nhau; một số chương trình merchant có quyền xử lý khiếu nại/đa kênh. RentGG không sao chép nguyên phí, số lượng listing hay tiền thành viên lịch sử.

## UC-32 — Coupon, red packet, rent-and-gift và free trial

**Các loại chương trình:** mã giảm giá; tiền thưởng khuyến mại; dùng thử có giới hạn; thuê gói dài tặng thêm thời gian; ưu đãi cho khách mới; mã của merchant; khuyến mại khi chia sẻ listing.

**Luồng chính:**
1. Admin/merchant tạo chương trình có điều kiện, hạn sử dụng, game, nhóm người dùng, ngân sách và mức sử dụng tối đa.
2. Hệ thống hiển thị giá gốc, giá giảm, thời lượng thực nhận và điều kiện áp dụng.
3. Checkout ghi phần thanh toán thật và phần ưu đãi tách biệt; hoàn tiền dùng đúng quy tắc phân bổ khuyến mại đã công bố.
4. Hệ thống phát hiện dùng thử nhiều tài khoản, tự thuê, giao dịch giả, referral giả hoặc lạm dụng coupon.
5. Nếu nghi ngờ gian lận, tạm dừng phần thưởng liên quan, xem xét chứng cứ và cho phép khiếu nại.

## UC-33 — Cửa sổ kiểm tra, hủy đơn và hoàn tiền

**Luồng chuẩn tham khảo:**
- Nhầm account/nhầm thời lượng: báo lỗi trong khoảng thời gian ngắn sau khi bắt đầu, mẫu tham khảo lịch sử Zuhaowan là khoảng 10 phút.
- Mật khẩu sai, không thể đăng nhập hoặc mô tả khác đáng kể: báo sớm; mẫu tham khảo là khoảng 15 phút với một số lỗi.
- Lỗi do chủ account hoặc account: có thể hoàn toàn bộ tiền thuê theo điều kiện/độ dài sử dụng; cọc của đơn đó được hoàn đầy đủ.
- Người thuê đổi ý: hoàn theo chính sách hủy rõ ràng; không mặc định được hoàn toàn bộ sau khi đã dùng dịch vụ.
- Bằng chứng sai/ khiếu nại cố ý: có thể trừ tiền thuê theo chính sách và giảm uy tín sau khi xác minh.
- Người đăng cố tình báo cáo giả người thuê: có thể hoàn tiền cho renter và xử lý người đăng.

**Tham số MVP của RentGG (cấu hình theo game, lưu trong `policy_snapshot` của đơn):**
| Tình huống | Tiền thuê | Tiền cọc |
|---|---|---|
| Hết hạn thanh toán (15 phút) | Không thu | `VOID` |
| Chủ account từ chối/quá hạn duyệt (30 phút) → `REJECTED` | Hoàn 100% | Hoàn 100% ngay |
| Người thuê hủy trước `start_at` ≥ `renter_free_cancel_minutes` (120 phút) | Hoàn 100% | Hoàn 100% ngay, không giữ |
| Người thuê hủy muộn hơn nhưng vẫn trước `start_at` | Hoàn `late_cancel_refund_bps` (50%), phần còn lại là doanh thu chủ account | Hoàn 100% ngay, không giữ |
| Chủ account hủy trước `start_at` (UC-40) | Hoàn 100% (< `owner_free_cancel_hours` → ghi `OWNER_LATE_CANCEL`) | Hoàn 100% ngay |
| Đến giờ mà GameAccount chưa `AVAILABLE` → `CANCELLED` (`OWNER_FAULT_NOT_READY`) | Hoàn 100% | Hoàn 100% ngay |
| `RENTER_LOGIN_FAILED`/`RENTER_NOT_AS_DESCRIBED` được chấp nhận (mở trong `complaint_window_minutes` = 15 phút kể từ lần xem credentials đầu) | Hoàn theo quyết định phân xử | Theo vòng đời cọc của đơn (không bị khấu trừ vì lỗi của chủ account) |
| Đơn `ENDED` bình thường | Không hoàn | Giữ `deposit_hold_hours` (72 giờ) rồi tự hoàn nếu không có khiếu nại |

- Không hủy được đơn đã `IN_RENTAL`; sự cố trong lúc thuê đi qua khiếu nại.
- Cọc luôn tính riêng theo từng đơn; hoàn/khấu trừ cọc của đơn này không ảnh hưởng đơn khác (UC-25).

Các khoảng thời gian là tham số cấu hình, phụ thuộc loại thuê/game/thiết bị. Mọi chính sách phải hiển thị trước checkout; không sao chép nguyên máy móc quy tắc cũ từ website Trung Quốc.

## UC-34 — Đồng bộ kho account nhiều kênh

Merchant có thể quản lý listing trên nhiều kênh được hỗ trợ. Một kho inventory trung tâm giữ lịch khả dụng và ghi nguồn đơn. Khi nhận đơn từ một kênh, mọi kênh khác phải cập nhật trạng thái trong thời gian quy định; nếu xung đột thời gian, hệ thống khóa listing và chuyển sang hỗ trợ. Không cung cấp khả năng đồng bộ cho kênh vi phạm điều khoản của bên thứ ba.

## UC-35 — Chất lượng listing, ảnh chứng minh và xếp hạng tin

**Luồng chính:**
1. Chủ account cung cấp ảnh/screenshot và thuộc tính theo mẫu từng game; hệ thống yêu cầu che thông tin cá nhân và thông tin khôi phục.
2. Tự động kiểm tra chất lượng, trùng ảnh, từ khóa cấm và bất thường; kiểm duyệt viên xử lý trường hợp đáng ngờ.
3. Listing có nhãn về tính chính xác, tỷ lệ đơn thành công, phản hồi, mức bảo đảm lỗi listing hoặc lịch sử tranh chấp được xác minh.
4. Xếp hạng kết hợp độ liên quan, chất lượng, uy tín và quảng cáo trả phí có gắn nhãn; không để quảng cáo làm mất thông tin cảnh báo thiết yếu.
5. Chủ account cập nhật trạng thái; hệ thống lưu phiên bản cũ để xử lý tranh chấp về mô tả tại thời điểm đặt hàng.

## UC-36 — Lịch payout, thời gian chờ và rút tiền

**Mục tiêu:** tránh giải ngân ngay khi đơn thuê kết thúc, tạo thời gian để phát hiện vi phạm bị báo muộn; đồng thời không giữ tiền vô thời hạn hoặc giữ tiền không liên quan đến tranh chấp.

**Tác nhân:** Chủ account, hệ thống RentGG, PSP/đối tác thanh toán, hỗ trợ/phân xử, đội tài chính.

**Luồng chính:**
1. Khi phiên thuê kết thúc, hệ thống xác minh trạng thái đơn, thời gian dùng thực tế, kết quả bàn giao/rotation nếu liên quan, khiếu nại đã mở và các tín hiệu rủi ro.
2. Phần doanh thu dự kiến của chủ account (tiền thuê trừ phí nền tảng và các điều chỉnh đã có căn cứ) được ghi vào `ORDER_REVIEW_HOLD`, không lập tức thành tiền rút được.
3. Hệ thống xác định `hold_until` theo chính sách hiển thị trước checkout, game, tier rủi ro, lịch sử người bán và loại sự kiện. Mặc định đề xuất RentGG: 72 giờ sau khi kết thúc đơn; account mới hoặc giao dịch/game có rủi ro cao có thể áp dụng thời gian dài hơn đã công bố, ví dụ 7 ngày.
4. Trong thời gian hold, hệ thống tiếp nhận báo cáo về cheat, bỏ trận, phá rank, tiêu hao vật phẩm, đổi thông tin, khóa account, chiếm phiên, chargeback hoặc vi phạm khác. Việc nhận báo cáo đủ điều kiện chuyển phần tiền liên quan sang `DISPUTE_HOLD` và mở hồ sơ phân xử.
5. Nếu hết thời gian hold mà không có tranh chấp hợp lệ hoặc tín hiệu rủi ro cần xác minh, hệ thống chuyển phần tiền phù hợp sang `AVAILABLE_FOR_WITHDRAWAL`.
6. Chủ account yêu cầu rút tiền; hệ thống kiểm tra KYC/định danh, phương thức nhận tiền, số dư khả dụng, ngưỡng rút, lệnh payout đang xử lý, các khoản hold và hạn chế theo quy tắc.
7. Hệ thống gửi lệnh qua PSP/đối tác thanh toán và ghi `PAYOUT_PROCESSING`. Sau khi PSP xác nhận thành công, chuyển sang `PAID_OUT`; nếu thất bại/hoàn ngược, chuyển `PAYOUT_FAILED`/`REVERSED` và xử lý đối soát.
8. Dashboard phân biệt rõ: doanh thu dự kiến, tiền chờ xét duyệt, tiền đang tranh chấp, tiền khả dụng, tiền đang chuyển, tiền đã thanh toán, tiền cọc và khoản khuyến mại không được rút.

**Ngoại lệ:**
- **Có khiếu nại trước khi hết hold:** chỉ giữ khoản tiền liên quan và phần dự phòng có cơ sở, hợp lý theo điều khoản; không mặc định đóng băng toàn bộ doanh thu không liên quan của người bán.
- **Có dấu hiệu nghiêm trọng nhưng chưa đủ chứng cứ:** có thể chuyển trạng thái rà soát tạm thời trong thời hạn được công bố; yêu cầu bổ sung chứng cứ và ghi lý do, người phê duyệt, ngày rà soát lại.
- **Game thông báo khóa account sau khi hold kết thúc nhưng trước khi payout:** chuyển phần tiền liên quan sang `DISPUTE_HOLD`, gửi thông báo cho chủ account và người thuê, xác minh nguyên nhân theo UC-18/UC-13.
- **Phát hiện sự việc sau khi đã payout:** không giả định có thể tự động lấy tiền từ tài khoản ngân hàng người bán. Mở claim hậu kỳ; xử lý bằng chứng, quyền khấu trừ số dư tương lai hoặc yêu cầu hoàn trả chỉ nếu đã có căn cứ hợp đồng/pháp lý và phương thức thanh toán hỗ trợ. Cho phép phản hồi/kháng nghị.
- **Chargeback/hoàn trả qua PSP:** giữ phần tiền cần thiết theo quy tắc PSP và chứng cứ; không lấy tiền cọc của người thuê để bù khoản phải trả cho người bán.
- **KYC chưa hoàn tất hoặc thông tin người nhận không khớp:** không gửi payout; yêu cầu cập nhật. Tiền tiếp tục ở trạng thái thích hợp, có thông báo rõ lý do.
- **Tài khoản marketplace bị khóa do điều tra:** chỉ đặt hold toàn tài khoản tài chính nếu có căn cứ cụ thể, phù hợp điều khoản/quy định và ghi rõ phạm vi/thời hạn; nếu rủi ro chỉ thuộc một đơn, chỉ hold số tiền liên quan.
- **PSP chưa hỗ trợ mô hình chia tiền/giữ tiền:** không tự vận hành ví/escrow trái điều kiện pháp lý; dùng quy trình thanh toán/chi trả được PSP và pháp luật chấp thuận.

**Quy tắc nghiệp vụ:**
1. **Hold sau thuê và thời gian chuyển tiền là hai đồng hồ riêng.** Thời gian review bắt đầu từ lúc đơn hoàn tất; thời gian PSP xử lý bắt đầu từ lúc lệnh rút hợp lệ được gửi.
2. Mức mặc định đề xuất: `STANDARD_HOLD = 72 giờ` sau khi kết thúc đơn. Với nhóm mới/rủi ro cao, có thể cấu hình `EXTENDED_HOLD = 7 ngày` và đánh giá lại sau giai đoạn thử nghiệm. Đây là chính sách đề xuất của RentGG, không phải con số luật định và không khẳng định là quy tắc giống hệt mọi nền tảng Trung Quốc.
3. GG租号 công bố thời gian tiền rút về tài khoản là 3 ngày làm việc sau khi thực hiện yêu cầu rút, giải thích khoảng chờ hỗ trợ chủ account có thời gian truy cứu hành vi phá rank/quảng cáo/bỏ trận. RentGG có thể tham khảo thời gian xử lý payout riêng, nhưng không được nhầm nó với hold 72 giờ từ lúc đơn thuê kết thúc.
4. Tranh chấp phải có ID, lý do, chứng cứ ban đầu, phạm vi tiền bị giữ, người xử lý, thời hạn cập nhật và cơ chế kháng nghị.
5. Chỉ phần tiền liên quan đến khiếu nại/chargeback và mức dự phòng có cơ sở mới bị hold; không dùng “có thể có rủi ro” làm lý do khóa toàn bộ số dư vô thời hạn.
6. Nếu không có khiếu nại trong cửa sổ thông thường, tiền tự chuyển sang khả dụng khi đủ điều kiện; tránh yêu cầu admin duyệt thủ công mọi đơn sạch.
7. Chính sách thời gian hold, trường hợp hold dài hơn, cách tính số tiền giữ và điều kiện giải phóng phải được công khai trước khi chủ account tham gia và/hoặc trước checkout theo phạm vi áp dụng.
8. Không tính cọc người thuê là doanh thu của người bán; không dùng cọc chưa phân xử để bù payout thường.
9. Không tính số dư coupon/red packet/free trial là tiền mặt rút được trừ khi điều khoản chương trình nói rõ.
10. Rotation thất bại không tự động chứng minh người thuê gây thiệt hại; chỉ giữ doanh thu khi có rủi ro gắn với đơn và căn cứ cụ thể, đồng thời account phải bị chặn khỏi đơn mới vì lý do bảo mật.
11. Không sửa/xóa bút toán đã ghi; điều chỉnh bằng bút toán bù có mã sự kiện và người phê duyệt.
12. Chỉ mở chức năng có số dư lưu trữ/hold/chi trả sau khi cấu trúc PSP, hợp đồng, kế toán và điều kiện pháp lý tại Việt Nam đã được xác nhận.

**Tham chiếu mô hình Trung Quốc:** quy tắc công khai của GG租号 đề cập payout đến tài khoản trong 3 ngày làm việc sau khi yêu cầu rút, nhằm tạo thời gian truy cứu vi phạm do người thuê gây ra. Quy tắc phân xử của GG租号 và Xubei cũng mô tả việc giữ tiền trong thời gian tranh chấp và xử lý sau khi có chứng cứ. Các mốc đó là tài liệu của nền tảng cụ thể; RentGG cần áp chính sách riêng được công khai và thẩm định.

**Tiêu chí nghiệm thu:**
- Doanh thu từ đơn mới kết thúc luôn vào trạng thái hold, không thể rút ngay.
- Hold 72 giờ và hold mở rộng được cấu hình/ghi log chính xác theo timezone thống nhất.
- Khi có tranh chấp, tiền liên quan không được payout trước quyết định.
- Khi tranh chấp đóng, chỉ giữ/khấu trừ đúng số tiền quyết định; phần dư được giải phóng.
- Dashboard giải thích được vì sao tiền đang pending và ngày/điều kiện dự kiến khả dụng.
- Lệnh rút bị từ chối nếu dùng tiền đang hold, tiền cọc, tiền đang tranh chấp hoặc số dư khuyến mại không rút được.
- Có kiểm thử payout thất bại, PSP chậm, chargeback, khiếu nại đến muộn, yêu cầu trùng và tranh chấp chỉ ảnh hưởng một phần số dư.


## UC-37 — Xử lý hết giờ thuê khi người thuê vẫn đang trong trận / chưa đăng xuất

**Mục tiêu:** xử lý công bằng khi thời gian thuê kết thúc giữa trận đấu hoặc người thuê chưa đăng xuất, đồng thời bảo vệ chủ account khỏi việc sử dụng vượt giờ, ghép đơn chồng và thiệt hại do phiên bị thu hồi.

**Tác nhân:** Người thuê, chủ account, hệ thống RentGG, người thuê kế tiếp, bộ phận hỗ trợ.

**Nguyên tắc v8:** RentGG không có client/launcher nên không thể tự đẩy người thuê ra khỏi game. **Đổi mật khẩu (rotation, UC-39) là cơ chế kết thúc phiên duy nhất.** Hiệu quả phụ thuộc cờ `password_change_kicks_sessions` của game: nếu `true`, đổi mật khẩu làm phiên đang chạy bị đăng xuất; nếu `false`, phiên hiện tại có thể vẫn còn đến khi người thuê tự thoát, chủ account phải dùng thêm chức năng "đăng xuất mọi thiết bị" của game (nếu có) và hệ thống phải hiển thị giới hạn này cho chủ account khi đăng listing.

**Tiền điều kiện:**
- Đơn ở trạng thái `IN_RENTAL`, có `start_at`, `scheduled_end_at`, `block_end_at` và `end_mode` được công bố trước checkout (lưu trong `policy_snapshot`).
- Listing khai báo một trong các chế độ `end_mode`: `FORCE_AT_END`, `GRACE_FOR_CURRENT_MATCH`, hoặc `STAY_ONLINE_UNTIL_RECLAIM` (chế độ kiểu "到时不下线", chỉ bật theo lựa chọn rõ ràng của chủ account).
- `block_end_at` = `scheduled_end_at` với `FORCE_AT_END` và `STAY_ONLINE_UNTIL_RECLAIM`; = `scheduled_end_at + grace_minutes` (mặc định 10 phút) với `GRACE_FOR_CURRENT_MATCH`. Lịch của GameAccount bị khóa đến `block_end_at` (exclusion constraint).
- Hệ thống không cho hai người thuê có quyền sử dụng đồng thời cùng một account.

**Luồng chính đề xuất của RentGG:**
1. Trước khi hết giờ 10 phút (job `sendEndingReminders`), hệ thống thông báo thời điểm kết thúc, chế độ `end_mode` và (từ Phase 1) lựa chọn gia hạn.
2. (Phase 1) Người thuê có thể chọn **Gia hạn** và thanh toán phần thời gian bổ sung; hệ thống chỉ gia hạn sau khi thanh toán thành công và không chồng lịch.
3. Nếu chưa gia hạn, hệ thống nhắc người thuê lưu tiến trình/thoát game đúng hạn và nhắc rằng mật khẩu sẽ bị đổi sau giờ kết thúc.
4. Khi đến `block_end_at`, job `endDueOrders` chuyển đơn sang `ENDED`, ghi `actual_end_at`, chấm dứt quyền xem credentials, cọc chuyển `POST_RENTAL_HOLD`. Xử lý GameAccount theo `end_mode`:
   - `FORCE_AT_END`: `block_end_at = scheduled_end_at`. GameAccount chuyển `ROTATION_PENDING`; chủ account được thông báo đổi mật khẩu ngay (UC-39). Nếu `password_change_kicks_sessions = true`, đổi mật khẩu sẽ đẩy người thuê ra.
   - `GRACE_FOR_CURRENT_MATCH`: `block_end_at = scheduled_end_at + grace_minutes`. Từ `scheduled_end_at` người thuê được báo chỉ hoàn tất trận hiện tại, không bắt đầu trận mới; đến `block_end_at` GameAccount chuyển `ROTATION_PENDING` như trên. RentGG không tự xác định tình trạng trận.
   - `STAY_ONLINE_UNTIL_RECLAIM`: `block_end_at = scheduled_end_at`. GameAccount chuyển `AWAITING_RECLAIM` (người thuê có thể vẫn còn trong game); listing không nhận đơn mới cho đến khi account `AVAILABLE`. Khi chủ account bấm "Thu hồi" (`reclaim`), GameAccount chuyển `ROTATION_PENDING` và chủ account đổi mật khẩu.
5. Chủ account hoàn tất rotation (UC-39) → GameAccount `AVAILABLE`. Hệ thống ghi thời điểm rotation, thời gian từ `actual_end_at` đến rotation và mã lý do.
6. Tiền thuê được tính theo chính sách đã công bố; không tự động phạt hoặc khấu trừ cọc chỉ dựa trên việc trận chưa kết thúc.
7. Nếu phát sinh thiệt hại vì người thuê cố tình không thoát, tiếp tục chơi sau giờ kết thúc hoặc làm ảnh hưởng đến người thuê kế tiếp, chủ account mở khiếu nại `OWNER_DAMAGE` kèm bằng chứng trong thời gian giữ cọc; phân xử theo UC-13/UC-27.

**Luồng ngoại lệ:**
- **Người thuê đang trong trận nhưng không có chức năng xác minh trận:** nền tảng không tự khẳng định người thuê nói thật. Cho phép tối đa một yêu cầu ân hạn theo đơn, trong thời hạn yêu cầu xác định trước; trường hợp có nghi ngờ thì chuyển hỗ trợ, không tự động kéo dài vô hạn.
- **Người thuê không thoát và game có `password_change_kicks_sessions = false`:** đổi mật khẩu không đẩy được phiên hiện tại; chủ account dùng "đăng xuất mọi thiết bị" của game nếu có; hệ thống ghi nhận quá hạn, có thể hạn chế quyền thuê mới nếu tiếp tục vi phạm; thiệt hại xử lý qua khiếu nại cọc theo điều khoản đã chấp nhận và chứng cứ.
- **Chủ account không đổi mật khẩu sau khi kết thúc:** GameAccount ở `ROTATION_PENDING`/`ROTATION_OVERDUE`; nếu đơn kế tiếp đến giờ mà account chưa `AVAILABLE`, đơn kế tiếp bị hủy (`OWNER_FAULT_NOT_READY`) và hoàn 100% (UC-39).
- **Chủ account thu hồi sớm:** nếu không có căn cứ khẩn cấp/vi phạm, xử lý như chủ account chấm dứt trái quy tắc; hoàn tiền thuê chưa sử dụng theo chính sách.
- **Người thuê kế tiếp đã đặt lịch:** không để phiên cũ và đơn mới chạy chồng. Đơn kế tiếp chỉ chuyển `IN_RENTAL` khi GameAccount đã `AVAILABLE` (tức là đã rotation sau đơn trước); nếu không, đơn kế tiếp bị hủy và hoàn 100% theo UC-39.
- **Game phạt bỏ trận khi bị ngắt:** không mặc định nền tảng hoặc người thuê phải chịu mọi khoản thiệt hại. Điều tra nguyên nhân, thời điểm cảnh báo, lựa chọn chính sách của listing, khả năng gia hạn và hành vi của các bên.
- **Người thuê báo “đang trong trận” sau khi đã quá hạn:** không tự động cấp thêm thời gian; yêu cầu bằng chứng và phân xử nếu chủ account đã bị ảnh hưởng hoặc có dấu hiệu lạm dụng.

**Quy tắc nghiệp vụ (đề xuất):**
1. Thời gian ân hạn mặc định là 10 phút, chỉ dùng để kết thúc trận đang diễn ra; không phải 10 phút miễn phí để bắt đầu một trận mới. Chủ account có thể chọn chính sách chặt hơn nếu hiển thị rõ trước thanh toán.
2. Một đơn chỉ có tối đa một lần ân hạn không trả phí; thời lượng có thể cấu hình theo game và phải được công bố trước checkout. Nếu game không thể xác định trạng thái trận, RentGG không cam kết tự phát hiện trận đang diễn ra.
3. Gia hạn trả phí luôn ưu tiên hơn ân hạn nếu người thuê chủ động gia hạn trước giờ hết hạn. Gia hạn chỉ có hiệu lực sau thanh toán thành công và không được làm chồng lịch.
4. `STAY_ONLINE_UNTIL_RECLAIM` là lựa chọn riêng của chủ account, không phải mặc định toàn sàn. Mô tả rõ rằng phiên có thể bị kết thúc khi chủ account thu hồi và đổi mật khẩu; account không nhận đơn mới trong khi `AWAITING_RECLAIM`.
5. Không bắt đầu đơn tiếp theo khi GameAccount chưa `AVAILABLE`. Đổi mật khẩu là cơ chế kết thúc phiên duy nhất; với game `password_change_kicks_sessions = false`, listing phải hiển thị cảnh báo cho chủ account rằng phiên cũ có thể chưa bị ngắt ngay.
6. Nếu người thuê chủ động bắt đầu trận mới trong thời gian ân hạn hoặc cố ý kéo dài việc bàn giao, có thể mất quyền gia hạn ưu đãi, bị tính phí vượt giờ đã công bố hoặc bị xử lý theo quy tắc vi phạm sau xác minh.
7. Mọi khấu trừ tiền cọc phải có điều khoản được người thuê chấp nhận, bằng chứng và cơ hội phản hồi; không dùng tự động khấu trừ chỉ vì game vẫn mở hoặc phiên chưa ngắt.
8. Không hứa bảo vệ khỏi mọi hình phạt trong game: nền tảng không có quyền sửa kết quả trận đấu hay khôi phục điểm rank trừ khi game cung cấp cơ chế được phép.

**Can thiệp thủ công (bổ sung):**
- **Hỗ trợ:** có thể cấp một lần ân hạn đặc biệt (có lý do, không chồng lên lịch đơn kế tiếp, không vượt trần cấu hình); kết thúc đơn sớm và chặn xem credentials khi chủ account khiếu nại khẩn (UC-43); yêu cầu chủ account đổi mật khẩu; hủy/hoàn tiền đơn kế tiếp bị ảnh hưởng.
- **Chủ account:** bấm "Thu hồi" (`reclaim`) với chế độ `STAY_ONLINE_UNTIL_RECLAIM` để chuyển `ROTATION_PENDING`, sau đó đổi mật khẩu qua luồng rotation.
- Mọi can thiệp không được tạo hai phiên đồng thời và phải ghi `actual_end_at`, lý do, người thực hiện.

**Tham chiếu mô hình Trung Quốc:** trang hướng dẫn sản phẩm của Zuhaoxiu nêu thời gian thuê được cộng thêm 10 phút; nếu chủ account bật `到时不下线`, phiên không bị ngắt ngay khi hết giờ và sẽ bị kết thúc khi chủ account hoặc người thuê sau cần sử dụng; nếu không bật, hệ thống đẩy người thuê ra khi hết giờ. GG租号 yêu cầu người thuê thoát khi hết thời gian và quy định trách nhiệm đối với thiệt hại do không thoát. Đây là các hành vi được công bố của từng nền tảng/sản phẩm, không phải một quy tắc thống nhất cho mọi marketplace Trung Quốc.

**Tiêu chí nghiệm thu:**
- Có nhắc giờ hết hạn và lựa chọn gia hạn trước khi kết thúc.
- Không có hai phiên thuê đồng thời cho cùng một account.
- Hệ thống xử lý đúng cả ba chế độ `FORCE_AT_END`, `GRACE_FOR_CURRENT_MATCH`, `STAY_ONLINE_UNTIL_RECLAIM`: `block_end_at` tính đúng; GameAccount chuyển `ROTATION_PENDING` hoặc `AWAITING_RECLAIM` đúng chế độ.
- Không có bước nào phụ thuộc client/launcher; kết thúc phiên luôn thông qua rotation mật khẩu.
- Listing của game có `password_change_kicks_sessions = false` hiển thị cảnh báo cho chủ account.
- Ghi được thời gian dự kiến/kết thúc thực tế, ân hạn, thời điểm rotation và tranh chấp.
- Không tự động phạt chỉ từ trạng thái game vẫn mở.
- Khi đơn tiếp theo bị ảnh hưởng do phiên cũ chưa kết thúc, có luồng thông báo, hủy/hoàn tiền hoặc hỗ trợ rõ ràng.


---

## UC-38 — Tự động match nhu cầu và nhận đơn theo listing đã bật AUTO_ACCEPT

**Mục tiêu:** người thuê có thể tìm account phù hợp và đặt thuê ngay mà không cần chủ account duyệt từng đơn, trong phạm vi chủ account đã cho phép trước qua cấu hình listing.

**Tác nhân:** Người thuê, chủ account, hệ thống RentGG, PSP/cổng thanh toán, dịch vụ kiểm duyệt game.

**Tiền điều kiện:**
- Listing ở trạng thái `ACTIVE`, game có trạng thái quyền niêm yết `ALLOWED`; listing chưa bị khiếu nại/đình chỉ; GameAccount không ở `ROTATION_PENDING`, `ROTATION_OVERDUE`, `AWAITING_RECLAIM` hay `SUSPENDED_SECURITY_REVIEW`.
- Chủ account đã xác minh và bật `AUTO_ACCEPT` cho listing cụ thể. **Game của listing phải có `handover_mode = STATIC_CREDENTIAL`; với `OWNER_ASSISTED_OTP`, hệ thống không cho bật `AUTO_ACCEPT`** vì bàn giao cần chủ account trực tiếp đưa mã (UC-08).
- Chủ account đã xác nhận điều kiện được áp dụng cho các đơn tự động: giá, đơn vị thời gian, thời lượng tối thiểu/tối đa, lịch khả dụng, tiền đặt cọc bắt buộc, giới hạn sử dụng, chính sách hủy và chế độ kết thúc phiên.
- Chính sách giá và điều kiện được hiển thị cho người thuê trước khi thanh toán.

**Luồng chính:**
1. Người thuê nhập nhu cầu: game/region, thời điểm bắt đầu, thời lượng, ngân sách, đặc điểm account và điều kiện sử dụng.
2. Hệ thống tìm các listing đáp ứng toàn bộ điều kiện bắt buộc, sau đó xếp hạng theo mức phù hợp, giá, đánh giá, tỷ lệ hoàn tất và độ tin cậy.
3. Hệ thống loại listing không đủ điều kiện: lịch chồng lấn, trạng thái game `REVIEW_REQUIRED`/`PROHIBITED`/`SUSPENDED`, chủ account chưa xác minh, account đang có đơn, đang đổi mật khẩu, bị khóa, có cảnh báo an toàn hoặc người thuê không đáp ứng điều kiện.
4. Người thuê chọn listing phù hợp và xem tổng thanh toán (tiền thuê + cọc), thời gian giữ cọc, quy tắc sử dụng, chính sách hủy, chế độ bàn giao và chính sách khi hết giờ.
5. Người thuê chấp nhận điều khoản riêng của đơn; hệ thống tạo đơn `PENDING_PAYMENT` và người thuê thanh toán tiền thuê + cọc.
6. Trong một giao dịch nguyên tử, hệ thống khóa lịch của GameAccount (exclusion constraint); kiểm tra lại availability, rủi ro, điều kiện người thuê và trạng thái listing để chống đặt đồng thời.
7. Khi webhook thanh toán hợp lệ đến và listing bật `AUTO_ACCEPT`, hệ thống tự chuyển đơn sang `CONFIRMED` mà không cần chủ duyệt từng đơn, cọc chuyển `HELD` và gửi thông báo cho cả hai bên.
8. Đến `start_at`, nếu GameAccount `AVAILABLE`, đơn chuyển `IN_RENTAL` và credentials xem được theo UC-08.
9. Hệ thống lưu phiên bản chính sách listing mà chủ đã đồng ý, dữ liệu checkout, thời điểm thanh toán, sự kiện chấp nhận tự động và thông báo gửi đi.
10. Chủ account có thể tắt `AUTO_ACCEPT` cho các đơn tương lai. Việc tắt không hủy hồi tố các đơn đã xác nhận; các đơn đó tuân theo điều kiện hủy đã công bố.

**Luồng thay thế/ngoại lệ:**
- **Listing không bật `AUTO_ACCEPT` (duyệt thủ công, `MANUAL_APPROVAL`):** sau thanh toán đơn chuyển `AWAITING_OWNER_APPROVAL`; chủ account duyệt trong 30 phút → `CONFIRMED`; từ chối hoặc quá hạn → `REJECTED`, hoàn 100% tiền thuê + cọc.
- **Chủ account cố bật `AUTO_ACCEPT` cho listing thuộc game `OWNER_ASSISTED_OTP`:** từ chối với thông báo lý do; nếu game đổi sang `OWNER_ASSISTED_OTP` sau khi listing đã bật, hệ thống tự tắt `AUTO_ACCEPT` cho đơn mới và thông báo chủ account.
- **Lịch bị người khác đặt trước cùng lúc:** chỉ một giao dịch được chiếm lịch; các yêu cầu còn lại nhận thông báo account vừa hết khả dụng và không bị thu tiền.
- **Thanh toán thất bại hoặc timeout:** giải phóng giữ chỗ; đơn chuyển `EXPIRED`, cọc `VOID`; không phát thông tin truy cập.
- **GameAccount đang `ROTATION_PENDING`/`ROTATION_OVERDUE`/`AWAITING_RECLAIM`/`SUSPENDED_SECURITY_REVIEW`:** chặn tự động nhận đơn, hiển thị không khả dụng.
- **Game/ToS thay đổi hoặc có yêu cầu gỡ hợp lệ:** chặn nhận đơn mới và chuyển listing sang rà soát; các đơn đã xác nhận được chuyển cho quy trình hỗ trợ/pháp chế.
- **Người thuê có dấu hiệu lạm dụng hoặc chưa đáp ứng định danh/độ tuổi:** không tự động nhận đơn; yêu cầu xác minh bổ sung hoặc từ chối theo chính sách.
- **Chủ account muốn duyệt từng renter:** chọn `MANUAL_APPROVAL`; hệ thống không tự động vượt qua lựa chọn này.

**Quy tắc nghiệp vụ:**
1. `AUTO_ACCEPT` là tùy chọn theo từng listing, mặc định chỉ bật sau thao tác chủ động của chủ account; không tự bật khi đăng tin; **không bật được khi game có `handover_mode = OWNER_ASSISTED_OTP`**.
2. Bật `AUTO_ACCEPT` đồng nghĩa chủ account ủy quyền cho hệ thống xác nhận mọi đơn khớp điều kiện đã công bố trong thời gian bật. Chủ account không cần duyệt từng đơn hợp lệ.
3. Không được tự ý thay đổi giá, đặt cọc hoặc điều kiện quan trọng sau khi đơn đã xác nhận; thay đổi chỉ áp dụng cho đơn mới hoặc cần người thuê đồng ý lại nếu luật/quy trình yêu cầu.
4. Chỉ `ALLOWED` được phép nhận đơn tự động. `REVIEW_REQUIRED` và các trạng thái khác phải fail-closed cho đến khi được giải quyết.
5. Hệ thống phải dùng khóa/transaction chống race condition, không bao giờ cấp hai đơn có quyền truy cập trùng giờ cho cùng một account.
6. Không tự động match theo tiêu chí nhạy cảm không cần thiết; tiêu chí xếp hạng phải có thể giải thích, không phân biệt đối xử trái quy định.
7. Quyết toán, cọc và phí phải ghi ledger và liên kết duy nhất với `order_id`.
8. Chủ account được quyền tạm dừng nhận đơn tương lai; không được dùng việc tắt AUTO_ACCEPT để né nghĩa vụ với đơn đang có hiệu lực.

**Can thiệp thủ công (bổ sung):**
- **Chủ account:** chuyển `MANUAL_APPROVAL` hoặc tắt `AUTO_ACCEPT` bất cứ lúc nào (chỉ áp dụng cho đơn mới); muốn hủy đơn đã xác nhận phải đi qua UC-40.
- **Hỗ trợ/Admin:** có thể đặt `AUTO_ACCEPT_SUSPENDED` cho một listing hoặc một chủ account khi có dấu hiệu bất thường, khiếu nại, thay đổi ToS hoặc yêu cầu gỡ hợp lệ. Với đơn đã xác nhận nhưng chưa bàn giao, có thể tạm giữ bàn giao và chuyển hỗ trợ rà soát (theo ngoại lệ của UC-17). Nếu phải hủy, hoàn tiền người thuê theo UC-40/UC-17 và không phạt chủ account khi lỗi thuộc nền tảng hoặc nguyên nhân bên ngoài.
- Mọi can thiệp ghi nhận theo UC-42 và thông báo cho cả hai bên.

**Tiêu chí nghiệm thu:**
- Đơn khớp listing `AUTO_ACCEPT` được xác nhận không cần chủ duyệt.
- Listing không bật `AUTO_ACCEPT` luôn chờ chủ xác nhận (`AWAITING_OWNER_APPROVAL`), quá 30 phút thì `REJECTED` và hoàn 100%.
- Không thể bật `AUTO_ACCEPT` (giao diện và API) cho listing thuộc game `OWNER_ASSISTED_OTP`.
- Kiểm thử tải đồng thời không tạo hai đơn cùng giữ một account/khung thời gian.
- Account chưa đổi mật khẩu thành công không thể nhận đơn mới.
- Mọi đơn đều lưu điều khoản/giá/lịch đã được chủ cho phép tại thời điểm xác nhận.
- Nếu payment fail, lịch và số tiền được giữ phải được giải phóng/hoàn theo trạng thái đã định.

---

## UC-39 — Tự động thu hồi quyền truy cập và đổi mật khẩu khi hủy đơn hoặc hết thời gian

**Mục tiêu:** ngăn người thuê tiếp tục truy cập account sau khi đơn bị hủy hoặc hết hạn, giảm rủi ro người thuê cũ dùng lại mật khẩu và bảo vệ chủ account trước khi cho đơn tiếp theo.

**Phạm vi v8:** **rotation thủ công là mặc định của MVP** — hệ thống tự động thu hồi quyền xem credentials và đưa GameAccount vào `ROTATION_PENDING`, còn việc đổi mật khẩu do chủ account thực hiện trên game rồi nhập mật khẩu mới vào RentGG. Tự động đổi mật khẩu chỉ cân nhắc ở Phase sau, riêng cho game có cơ chế chính thức, hợp lệ và đã được kiểm tra. Không có client/launcher.

**Tác nhân:** Hệ thống RentGG, chủ account, người thuê, người thuê kế tiếp, bộ phận hỗ trợ.

**Tiền điều kiện:**
- GameAccount đã qua xác minh thông tin khôi phục (UC-44) và có một `CredentialVersion` `ACTIVE` (mã hóa AES-256-GCM).
- Không lưu mật khẩu dạng văn bản thuần trong DB/log, không đưa secret vào analytics hoặc thông báo.
- Game cấu hình `password_change_kicks_sessions` để hệ thống biết đổi mật khẩu có ngắt phiên đang chạy hay không.
- SLA rotation `rotation_sla_minutes` = 60 phút.

**Trạng thái GameAccount (`security_state`):**
| Trạng thái | Ý nghĩa |
|---|---|
| `PENDING_VERIFICATION` | Mới tạo, chờ admin xác minh thông tin khôi phục (UC-44); chưa được đăng listing. |
| `AVAILABLE` | Sẵn sàng; credential hiện hành chưa bị lộ cho người thuê nào. |
| `IN_USE` | Đơn đang `IN_RENTAL`; người thuê xem được credentials. |
| `AWAITING_RECLAIM` | Đơn `STAY_ONLINE_UNTIL_RECLAIM` đã kết thúc, chờ chủ account bấm thu hồi. |
| `ROTATION_PENDING` | Chờ chủ account đổi mật khẩu (trong SLA 60 phút). |
| `ROTATION_OVERDUE` | Quá SLA mà chưa đổi; hệ thống nhắc lại và ghi tín hiệu. |
| `SUSPENDED_SECURITY_REVIEW` | Nghi chiếm account/sự cố bảo mật (UC-43); về `AVAILABLE` cần hai người duyệt. |

Chuyển trạng thái hợp lệ: `PENDING_VERIFICATION` → `AVAILABLE` ⇄ `IN_USE` → `ROTATION_PENDING` → `AVAILABLE`; `IN_USE` → `AWAITING_RECLAIM` → `ROTATION_PENDING`; `ROTATION_PENDING` → `ROTATION_OVERDUE` → `AVAILABLE` (khi đổi xong); bất kỳ trạng thái nào → `SUSPENDED_SECURITY_REVIEW`.

**Các sự kiện kích hoạt đổi mật khẩu:**
- Đơn `ENDED` (đến `block_end_at`) với `FORCE_AT_END` hoặc `GRACE_FOR_CURRENT_MATCH` → `ROTATION_PENDING` ngay.
- Đơn `ENDED` với `STAY_ONLINE_UNTIL_RECLAIM` → `AWAITING_RECLAIM`; chủ account bấm "Thu hồi" → `ROTATION_PENDING`.
- Đơn kết thúc sớm do sự cố bảo mật hoặc người thuê vi phạm (UC-43) → `SUSPENDED_SECURITY_REVIEW` (không đi luồng rotation thường).
- Đơn bị hủy trước `start_at`: credentials chưa từng hiển thị (chỉ xem được khi `IN_RENTAL`) nên **không cần rotation**; GameAccount giữ `AVAILABLE`. Ghi quyết định này vào audit.

**Luồng chính (rotation thủ công — mặc định MVP):**
1. Khi đơn `ENDED`, hệ thống chấm dứt quyền xem credentials của người thuê và chuyển GameAccount sang `ROTATION_PENDING` (hoặc `AWAITING_RECLAIM` theo `end_mode`); ghi thời điểm bắt đầu SLA.
2. Hệ thống thông báo chủ account: "Đổi mật khẩu account ngay; account không nhận đơn mới cho đến khi hoàn tất". Với game `password_change_kicks_sessions = false`, thông báo thêm việc dùng "đăng xuất mọi thiết bị" của game nếu có.
3. Chủ account đổi mật khẩu trực tiếp trên game bằng quyền kiểm soát thông tin khôi phục của mình (UC-44). RentGG không vượt qua MFA/OTP, email/điện thoại khôi phục hoặc kiểm soát bảo mật của nhà phát hành.
4. Chủ account nhập mật khẩu mới vào RentGG (`POST /game-accounts/{id}/rotation`) và cam kết đã đăng xuất mọi thiết bị theo cơ chế game hỗ trợ.
5. Hệ thống tạo `CredentialVersion` mới `ACTIVE` (mã hóa), chuyển version cũ sang `RETIRED`, ghi audit (người thực hiện, thời điểm, đơn kích hoạt; không ghi mật khẩu).
6. GameAccount chuyển `AVAILABLE`; listing có thể nhận đơn mới và đơn kế tiếp đã đặt có thể bắt đầu.
7. Người thuê cũ không bao giờ xem được mật khẩu mới.
8. **Quá SLA 60 phút** mà chưa đổi: job `markRotationOverdue` chuyển `ROTATION_OVERDUE`, nhắc lại chủ account và ghi tín hiệu (UC-14). Chủ account vẫn có thể hoàn tất rotation để về `AVAILABLE`.
9. **Đơn kế tiếp đến `start_at` mà GameAccount chưa `AVAILABLE`** (`ROTATION_PENDING`, `ROTATION_OVERDUE`, `AWAITING_RECLAIM` hoặc `SUSPENDED_SECURITY_REVIEW` do lỗi chủ account): job `startDueOrders` chuyển đơn kế tiếp sang `CANCELLED` với `cancel_reason = OWNER_FAULT_NOT_READY`, **hoàn 100% tiền thuê + cọc** cho người thuê kế tiếp, thông báo hai bên và **ghi tín hiệu lạm dụng chủ account** (UC-14; tương đương một lần chủ hủy sát giờ theo UC-40).

**Luồng ngoại lệ:**
- **Chủ account nhập mật khẩu mới nhưng thực tế chưa đổi trên game:** người thuê kế tiếp hoặc người thuê cũ có thể phát hiện; nếu có bằng chứng người thuê cũ vẫn đăng nhập được, ghi tín hiệu đã xác minh cho chủ account và xử lý theo UC-14.
- **OTP/MFA yêu cầu thao tác chủ account:** chủ account tự thực hiện trên game; RentGG không lưu OTP dài hạn hoặc lách xác thực.
- **Mật khẩu đổi xong nhưng phiên cũ vẫn còn** (game `password_change_kicks_sessions = false`): yêu cầu chủ account đăng xuất mọi thiết bị theo cơ chế game; nếu chủ báo người thuê cũ vẫn chơi sau giờ, mở khiếu nại `OWNER_DAMAGE` (UC-27).
- **Chủ account không phản hồi:** giữ account ngoài kho khả dụng; các đơn kế tiếp đến giờ bị hủy theo bước 9.
- **Phát hiện người thuê đã đổi mật khẩu/thông tin khôi phục:** không đi rotation thường; chuyển UC-43.
- **Đơn đang tranh chấp:** rotation vẫn tiến hành vì mục tiêu bảo mật; hoàn tiền/khấu trừ cọc theo luồng tranh chấp riêng, không bị quyết định bởi kết quả rotation.
- **(Phase sau) Game có cơ chế đổi mật khẩu tự động hợp lệ:** rotation tự động là tác vụ idempotent theo `order_id + trigger`, retry có giới hạn; thất bại thì quay về luồng thủ công ở trên.

**Quy tắc nghiệp vụ:**
1. Mỗi đơn đã `IN_RENTAL` kết thúc đều phải rotation trước khi GameAccount nhận đơn kế tiếp; chuyển trạng thái là idempotent — lặp lại event không tạo nhiều yêu cầu rotation hay mở lại quyền xem đã đóng.
2. `ROTATION_PENDING`, `ROTATION_OVERDUE`, `AWAITING_RECLAIM`, `SUSPENDED_SECURITY_REVIEW` và `PENDING_VERIFICATION` chặn matching/nhận đơn và chặn đơn kế tiếp bắt đầu.
3. Không cung cấp mật khẩu mới cho người thuê cũ; nhân viên cũng không xem được mật khẩu mới.
4. Không lưu mật khẩu plaintext, recovery codes hoặc OTP trong log, ticket, email, chat hỗ trợ hay dữ liệu phân tích.
5. Rotation thủ công là mặc định MVP; tự động chỉ ở Phase sau với game có cơ chế chính thức hợp lệ, có sự cho phép rõ ràng của chủ account.
6. Mỗi rotation tạo `CredentialVersion` mới; version cũ `RETIRED` và không bao giờ được hiển thị lại.
7. SLA rotation 60 phút; quá hạn → `ROTATION_OVERDUE` + nhắc + tín hiệu. Đơn kế tiếp đến giờ mà account chưa `AVAILABLE` → `CANCELLED` (`OWNER_FAULT_NOT_READY`), hoàn 100% tiền thuê + cọc, ghi tín hiệu lạm dụng chủ account (UC-14, UC-40).
8. Việc đổi mật khẩu không thay thế việc xử lý khiếu nại hoặc bồi thường; mỗi việc là một luồng riêng.

**Can thiệp thủ công (bổ sung):**
- **Chủ account:** thực hiện rotation (nhập mật khẩu mới + cam kết đã đăng xuất mọi thiết bị); bấm "Thu hồi" với `AWAITING_RECLAIM`.
- **Hỗ trợ/Admin:** nhắc chủ account; đưa account vào `SUSPENDED_SECURITY_REVIEW` khi nghi ngờ; hủy đơn kế tiếp sớm hơn giờ bắt đầu nếu chắc chắn account không kịp sẵn sàng (hoàn 100%, `cancel_reason = OWNER_FAULT_NOT_READY`); gỡ tín hiệu `OWNER_FAULT_NOT_READY` khi xác định lỗi do hệ thống.
- **Đưa account từ `SUSPENDED_SECURITY_REVIEW` về `AVAILABLE`:** chỉ qua ApprovalRequest `ACCOUNT_SECURITY_RESTORE` có hai người duyệt (người duyệt khác người yêu cầu), sau khi chủ account đã rotation và xác nhận lại thông tin khôi phục (UC-44). Không có đường tắt.
- Không ai, kể cả Admin, được xem mật khẩu mới hoặc bỏ qua MFA/OTP của nhà phát hành.

**Tiêu chí nghiệm thu:**
- Khi đơn `ENDED`, GameAccount chuyển đúng `ROTATION_PENDING` hoặc `AWAITING_RECLAIM` theo `end_mode`, và quyền xem credentials chấm dứt.
- Đơn hủy trước `start_at` không tạo yêu cầu rotation.
- Rotation tạo `CredentialVersion` mới `ACTIVE`, version cũ `RETIRED`, GameAccount `AVAILABLE`; không log mật khẩu.
- Quá 60 phút chưa rotation → `ROTATION_OVERDUE` và có thông báo nhắc.
- Đơn kế tiếp đến `start_at` khi account chưa `AVAILABLE` → `CANCELLED` (`OWNER_FAULT_NOT_READY`), hoàn 100% tiền thuê + cọc, có tín hiệu lạm dụng gắn với chủ account.
- Không thể đưa account từ `SUSPENDED_SECURITY_REVIEW` về `AVAILABLE` khi chưa đủ hai người duyệt.
- Ghi log kiểm toán gồm actor/event/timestamp/result, không chứa mật khẩu hoặc OTP.

**Giới hạn:** "tự động đổi mật khẩu" là mục tiêu Phase sau theo từng loại game đã xác minh, không thể cam kết dùng được với mọi game. Không dùng tự động hóa để né MFA hoặc điều khoản nhà phát hành; nếu ToS cấm chia sẻ account thì cơ chế bảo mật này không làm giao dịch trở thành được phép.


---

## UC-40 — Chủ account hủy lịch đã được xác nhận trước giờ bắt đầu

**Mục tiêu:** cho chủ account hủy một lịch đã xác nhận khi có nhu cầu thực tế, đồng thời bảo vệ người thuê khỏi bị hủy sát giờ hoặc mất cơ hội thuê account khác.

**Tác nhân:** Chủ account, người thuê, hệ thống RentGG, PSP, hỗ trợ.

**Chính sách mặc định đề xuất:**
- Chủ account có thể hủy mà không chịu chế tài nền tảng nếu gửi yêu cầu **ít nhất 12 giờ trước `start_at`**.
- Mốc 12 giờ (`owner_free_cancel_hours`) là cấu hình khởi đầu của RentGG, không phải quy tắc pháp luật hoặc quy tắc chung của Trung Quốc. Có thể cấu hình khác theo game/listing, nhưng phải công khai khi người thuê đặt lịch và lưu phiên bản chính sách cùng đơn.
- Trong vòng 12 giờ trước giờ bắt đầu, hủy vẫn có thể thực hiện để tránh bàn giao account khi không an toàn, nhưng được xem là **hủy sát giờ** và kích hoạt hoàn tiền, ghi nhận độ tin cậy, cùng quy trình xem xét chế tài.
- Nếu đã đến giờ bắt đầu hoặc đã cấp quyền truy cập, xử lý theo luồng hủy sau khi bắt đầu/thu hồi truy cập; không dùng UC này để thay thế quy trình sự cố hoặc bảo mật.

**Tiền điều kiện:**
- Đơn ở trạng thái trước khi thuê: `CONFIRMED` hoặc `AWAITING_OWNER_APPROVAL` (với trạng thái sau, chủ account dùng "Từ chối" → `REJECTED`, hoàn 100%).
- Chủ account đã đăng nhập và có quyền quản lý listing/đơn đó.
- Chính sách hủy đã được người thuê chấp nhận tại checkout.

**Luồng chính — hủy đủ sớm:**
1. Chủ account mở lịch hoặc chi tiết đơn, chọn `Hủy lịch đã xác nhận`.
2. Hệ thống hiển thị thời gian còn lại đến giờ bắt đầu, hạn chót hủy miễn chế tài, tiền thuê/đặt cọc phải hoàn và các ảnh hưởng tới độ tin cậy.
3. Chủ account chọn lý do, nhập mô tả và xác nhận lần cuối.
4. Hệ thống xác minh yêu cầu được tạo ít nhất 12 giờ trước `start_at` theo chính sách đang lưu của đơn.
5. Hệ thống chuyển đơn thành `CANCELLED` với `cancel_reason = OWNER_CANCEL`, giải phóng khung giờ, ngăn cấp thông tin đăng nhập và hoàn 100% tiền thuê + cọc cho người thuê (cọc hoàn ngay, không giữ).
6. Hệ thống thông báo cho người thuê ngay, đề xuất tìm listing thay thế nếu có và ghi nhận lịch sử hủy.
7. Listing có thể tiếp tục nhận đơn nếu account vẫn an toàn và chủ account không tắt `AUTO_ACCEPT`.

**Luồng thay thế — hủy sát giờ:**
1. Chủ account gửi yêu cầu trong vòng 12 giờ trước giờ bắt đầu.
2. Hệ thống cảnh báo rõ đây là hủy sát giờ, yêu cầu chọn lý do và đính kèm bằng chứng nếu có tình huống khẩn cấp.
3. Hệ thống vẫn ưu tiên giải phóng người thuê khỏi nghĩa vụ thanh toán cho dịch vụ chưa được cung cấp, hoàn tiền và giải phóng cọc theo chính sách; không buộc người thuê chờ tranh chấp giữa nền tảng với chủ account.
4. Hệ thống ghi `OWNER_LATE_CANCEL`, cập nhật chỉ số độ tin cậy và có thể tạm tắt `AUTO_ACCEPT` khi vượt ngưỡng rủi ro.
5. Nếu cần phạt tiền, khấu trừ khoản bảo đảm của chủ account hoặc cấp voucher bù đắp, hệ thống chuyển sang quy trình phân xử. Không tự động lấy tiền từ chủ account nếu không có căn cứ hợp đồng, số dư khả dụng và quy trình phản hồi.
6. Chủ account có thể khiếu nại phân loại hủy sát giờ nếu sự cố nằm ngoài khả năng kiểm soát hoặc có bằng chứng hợp lệ.

**Luồng ngoại lệ:**
- **Sự cố khẩn cấp/bất khả kháng:** cho phép hủy ngay; ghi chứng cứ và chuyển người có thẩm quyền rà soát chế tài. Không buộc account bàn giao nếu có rủi ro bảo mật.
- **Lỗi account hoặc nghi ngờ bị chiếm quyền:** ưu tiên an toàn, đưa account vào `SUSPENDED_SECURITY_REVIEW`; chính sách phạt chỉ xác định sau khi xem xét nguyên nhân.
- **Chủ account không hủy nhưng account không sẵn sàng ở giờ bắt đầu** (chưa rotation sau đơn trước): hệ thống tự hủy với `cancel_reason = OWNER_FAULT_NOT_READY`, hoàn 100% tiền thuê + cọc và ghi tín hiệu lạm dụng chủ account, được tính như một lần hủy sát giờ (UC-39, UC-14).
- **Credential đã hiển thị cho người thuê:** chỉ xảy ra khi đơn đã `IN_RENTAL` — không thuộc UC-40; xử lý bằng kết thúc sớm/khiếu nại và rotation theo UC-39.
- **Đơn đã bắt đầu:** không dùng UC-40; chuyển sang quy trình kết thúc sớm, tính thời gian đã sử dụng và xử lý tranh chấp.
- **PSP không hoàn tiền tức thời:** khoản hoàn (Refund) và cọc vẫn ở trạng thái chờ (`PENDING`/`REFUND_PENDING`); gửi thông báo và đối soát đến khi hoàn tất, không đánh dấu `REFUNDED` trước khi có kết quả.
- **Chủ account liên tục hủy lịch:** cảnh báo, giảm ưu tiên tìm kiếm, giới hạn số đơn tự động hoặc đình chỉ AUTO_ACCEPT sau khi kiểm tra số lượng mẫu, lý do và kết quả kháng nghị.

**Quy tắc nghiệp vụ:**
1. Hủy đủ sớm mặc định là `>= 12 giờ` trước giờ bắt đầu. Đây là đề xuất của RentGG và phải được cấu hình/hiển thị, không phải con số được tuyên bố là lấy nguyên từ Trung Quốc.
2. Hủy dưới 12 giờ được ghi nhận là hủy sát giờ trừ khi phân xử xác nhận lỗi hệ thống, lỗi listing, sự cố an toàn hoặc bất khả kháng.
3. Người thuê phải được hoàn phần tiền dịch vụ chưa cung cấp và giải phóng tiền cọc theo chính sách; tranh chấp phí phạt của chủ account không được làm chậm vô lý việc hoàn tiền cho người thuê.
4. Chế tài với chủ account có thể là giảm điểm tin cậy, tạm ngừng AUTO_ACCEPT, giới hạn listing, khấu trừ khoản bảo đảm riêng nếu điều khoản cho phép, hoặc đình chỉ khi lặp lại. Không dùng tiền cọc của người thuê để bù khoản phạt của chủ account.
5. Chủ account chỉ được hủy lịch đã xác nhận qua luồng hệ thống để lưu audit log; không được tự ý đổi mật khẩu/thu hồi account để né lịch mà không thông báo.
6. Người thuê nhận thông báo ngay và có thể chọn tìm listing tương đương; voucher là phương án bù đắp bổ sung, không thay thế hoàn tiền bắt buộc theo chính sách áp dụng.
7. Mọi quy tắc phải hiển thị trước thanh toán và phù hợp điều kiện PSP/pháp luật Việt Nam.

**Tiêu chí nghiệm thu:**
- Hủy ở thời điểm `>= 12 giờ` được phân loại hủy đủ sớm theo cấu hình.
- Hủy sát giờ kích hoạt thông báo, hoàn tiền và bản ghi độ tin cậy.
- Không cấp credential cho đơn đã bị hủy; đơn hủy trước `start_at` không tạo yêu cầu rotation.
- Người thuê nhận hoàn 100% tiền thuê + cọc; cọc không qua thời gian giữ.
- Đơn bị hủy tự động vì `OWNER_FAULT_NOT_READY` được tính vào chỉ số hủy sát giờ của chủ account.
- Hệ thống giải phóng lịch mà không cho đặt trùng trong giai đoạn xử lý.
- Có log người yêu cầu, lý do, timestamp, chính sách phiên bản, khoản tiền hoàn và kết quả phân xử.
- Chủ account có quyền phản hồi/kháng nghị trước chế tài tài chính cuối cùng.


---

## UC-41 — Giữ tiền sau thuê, kiểm soát rủi ro và điều kiện cho phép rút

**Mục tiêu:** xác định rõ sự kiện nào ngăn người bán rút tiền, thời gian giữ tối thiểu, cách giữ theo từng đơn và cách giải phóng tiền khi rủi ro được xử lý.

**Tác nhân:** Hệ thống, chủ account, renter (người gửi khiếu nại), nhân viên phân xử, Finance, PSP.

**Tiền điều kiện:** tiền thuê đã được PSP xác nhận và được ghi vào ledger theo `order_id`; tài liệu/policy hold có phiên bản và được hiển thị cho người bán.

**Luồng chính:**
1. Khi đơn kết thúc, tính `seller_net_amount` theo giá, phí và các khoản đã được xác nhận. Chuyển khoản doanh thu này sang `ORDER_REVIEW_HOLD`.
2. Gắn các thuộc tính: `hold_started_at`, `hold_until`, `hold_policy_id`, `risk_tier`, `order_id`, `seller_id`, `game_id`.
3. Áp dụng chính sách:
   - **Đơn thường:** giữ 72 giờ kể từ `actual_end_at`.
   - **Người bán mới hoặc account/game được xếp rủi ro cao:** giữ tối đa theo policy mở rộng đã công bố, ví dụ 7 ngày; áp dụng theo tiêu chí rõ ràng, có thời điểm rà soát lại.
   - **Có báo cáo hợp lệ/khiếu nại/chargeback hoặc bằng chứng cheat:** chuyển khoản liên quan sang `DISPUTE_HOLD`, bất kể hold thường đã gần hết.
   - **Không có sự cố:** tự động giải phóng sang `AVAILABLE_FOR_WITHDRAWAL` khi hết `hold_until`.
4. Chủ account có thể xem chi tiết từng dòng tiền: đơn nguồn, lý do hold, thời điểm bắt đầu, thời điểm dự kiến mở, và cách gửi khiếu nại nếu không đồng ý.
5. Khi nhận khiếu nại, hệ thống mở dispute, đóng dấu thời gian và tính `hold_scope_amount` — giới hạn tối đa phần tiền đơn liên quan cùng khoản dự phòng hợp lý được policy cho phép.
6. Nhân viên phân xử thu thập bằng chứng của renter/chủ account, trạng thái game, log thời gian thuê và giải thích của hai bên; quyết định giải phóng, hoàn tiền, khấu trừ hoặc chuyển yêu cầu bồi thường.
7. Sau quyết định, hệ thống ghi bút toán bù và chỉ giải phóng phần dư không còn căn cứ hold. Người liên quan nhận thông báo kết quả và quyền kháng nghị.
8. Lệnh rút kiểm tra tổng `AVAILABLE_FOR_WITHDRAWAL` trừ các khoản đang bị reservation bởi payout khác; không rút từ `ORDER_REVIEW_HOLD`, `DISPUTE_HOLD`, tiền đặt cọc người thuê hoặc khoản bảo đảm listing.
9. Sau khi yêu cầu rút hợp lệ được gửi PSP, số tiền chuyển sang `PAYOUT_PROCESSING`. PSP xác nhận thì `PAID_OUT`; lỗi chuyển tiền thì `PAYOUT_FAILED` và tiền được đối soát trở về trạng thái phù hợp.

**Các case bắt buộc phải xử lý:**
- **Case A — Vừa kết thúc thuê, chưa có khiếu nại:** không được rút ngay; áp dụng hold thường 72 giờ.
- **Case B — Người thuê bị xác minh dùng cheat/để lại hành vi vi phạm:** giữ doanh thu của đơn liên quan và cọc theo điều khoản; phân xử rồi mới quyết toán.
- **Case C — Chủ account báo phá rank/tiêu hao vật phẩm muộn trong cửa sổ hold:** chuyển tiền từ hold thường sang dispute hold, không payout trong khi hồ sơ đang mở.
- **Case D — Account bị nhà phát hành khóa sau vài ngày:** mở quy trình hậu kỳ, yêu cầu bằng chứng về lý do và thời điểm; chỉ hold khoản liên quan khi có căn cứ. Không mặc định người thuê là nguyên nhân chỉ dựa vào thời điểm khóa.
- **Case E — Khiếu nại đến sau khi người bán đã rút:** xử lý bằng cơ chế claim hậu kỳ/đối trừ thu nhập tương lai nếu đã thỏa thuận và được phép; không giả định RentGG có thể tự lấy lại tiền từ ngân hàng.
- **Case F — Chủ account có 10 đơn sạch và 1 đơn đang tranh chấp:** mặc định chỉ giữ khoản liên quan đến đơn tranh chấp và phần dự phòng được phép; không đóng băng cả 10 đơn sạch nếu không có căn cứ về rủi ro toàn tài khoản.
- **Case G — Chủ account vừa bị đình chỉ vì dấu hiệu gian lận có hệ thống:** có thể tạm ngăn payout toàn tài khoản tài chính trong phạm vi cần thiết nếu điều khoản/luật cho phép, ghi rõ lý do, phạm vi, thời hạn rà soát và cơ chế kháng nghị.
- **Case H — PSP xử lý payout chậm 3 ngày làm việc:** hiển thị `PAYOUT_PROCESSING`; không nhầm khoản này là hold chờ khiếu nại và không cộng thêm một hold mới nếu policy không quy định.
- **Case I — Hủy đơn trước khi người thuê nhận credential:** hoàn tiền theo chính sách hủy, không bắt buộc hold doanh thu thuê chưa phát sinh; chỉ giữ các khoản có căn cứ khác như chargeback hoặc tranh chấp hợp lệ.
- **Case J — Chủ account chưa đổi mật khẩu khi đơn kết thúc (`ROTATION_PENDING`/`ROTATION_OVERDUE`):** chặn account khỏi đơn kế tiếp; không tự động suy diễn rằng toàn bộ doanh thu của chủ account bị mất. Chỉ giữ tiền nếu có tranh chấp/thiệt hại cụ thể liên quan; đơn kế tiếp bị hủy vì `OWNER_FAULT_NOT_READY` được hoàn từ `RENT_ESCROW`/`DEPOSIT_HELD` của chính đơn đó, không trừ vào doanh thu đã hold của đơn trước.
- **Case K — Cọc người thuê là sổ phụ riêng:** cọc nằm ở `DEPOSIT_HELD` (nợ phải trả người thuê), không bao giờ cộng vào số dư hold/khả dụng của chủ account cho đến khi phân xử quyết định khấu trừ (`DEPOSIT_DEDUCTED` → `SELLER_AVAILABLE`). Thời gian giữ cọc (`deposit_hold_hours`) và thời gian hold doanh thu (`seller_hold_hours`) là hai đồng hồ độc lập, cùng mặc định 72 giờ nhưng cấu hình riêng.
- **Case L — Chiếm account (UC-43):** khi mở `ACCOUNT_TAKEOVER`, cọc của đơn chuyển `DISPUTED`; doanh thu của chủ account từ đơn đó vẫn theo hold thường (chủ account là bên bị hại), trừ khi phân xử xác định khác.

**Quy tắc nghiệp vụ:**
1. Bắt buộc tách `ORDER_REVIEW_HOLD` (chờ kiểm tra sau đơn), `DISPUTE_HOLD` (đang tranh chấp), `AVAILABLE_FOR_WITHDRAWAL` (được rút), `PAYOUT_PROCESSING` (lệnh chuyển tiền đang xử lý) và `PAID_OUT` (đã trả).
2. Hold tiêu chuẩn 72 giờ là chính sách đề xuất của RentGG. Hold mở rộng 7 ngày hoặc giá trị khác phải được cấu hình theo game/risk tier, công khai trước giao dịch và đánh giá hiệu quả định kỳ.
3. Bộ đếm hold không thể kéo dài vô hạn bằng thao tác admin không có lý do; mỗi gia hạn cần mã nguyên nhân, người phê duyệt, thông báo và `review_by`.
4. Có thể tự động khóa phần tiền của đơn khi báo cáo đạt ngưỡng hợp lệ; quyết định khấu trừ cuối cùng phải theo UC-13/UC-27, có chứng cứ và cho các bên phản hồi.
5. Không được coi mọi lệnh cấm/khóa account sau thuê là do renter; xác định nguyên nhân theo tài liệu game, log và thời điểm.
6. Không cho renter và chủ account tự thỏa thuận bên ngoài để né quyết toán hoặc sửa trạng thái đơn; mọi thỏa thuận hoàn/khấu trừ phải ghi nhận trong hệ thống.
7. Các khoản deposit, seller listing bond, order proceeds và gói bảo vệ account phải có ledger phụ riêng, không được bù chéo tự động. **Cọc của người thuê là sổ phụ riêng (`DEPOSIT_HELD`), gắn 1–1 với `order_id`:** không dùng để bù chargeback, phí, tiền thuê hay thiệt hại của đơn khác; không tính vào số dư rút được của chủ account; chỉ ra khỏi sổ qua hoàn cho người thuê hoặc khấu trừ theo quyết định phân xử (UC-25, UC-27).
8. Chính sách cho phép rút không thay thế các nghĩa vụ về hoàn tiền, chargeback, thuế/kế toán, KYC/AML nếu áp dụng và điều kiện PSP.
9. Nếu pháp lý/PSP không cho phép RentGG trực tiếp giữ tiền, kiến trúc phải dùng đối tác phù hợp; các trạng thái hold phải phản ánh quyền điều khiển thực tế của PSP, không giả vờ rằng RentGG có thể đóng băng tiền ở ngân hàng.
10. Không mô tả 72 giờ hoặc 7 ngày như quy định pháp luật hay tiêu chuẩn bắt buộc của toàn thị trường Trung Quốc; đó là chính sách vận hành đề xuất cho RentGG, có tham chiếu một số nền tảng cụ thể.
11. Ánh xạ tên trạng thái với tài khoản ledger của bản chốt: `ORDER_REVIEW_HOLD` ↔ `SELLER_REVIEW_HOLD:{seller}`, `DISPUTE_HOLD` ↔ `SELLER_DISPUTE_HOLD:{seller}`, `AVAILABLE_FOR_WITHDRAWAL` ↔ `SELLER_AVAILABLE:{seller}`, `PAYOUT_PROCESSING` ↔ `SELLER_PAYOUT_PROCESSING:{seller}`; `PAID_OUT` tương ứng Withdrawal `PAID`.

**Can thiệp thủ công (bổ sung):**
- **Hỗ trợ:** có thể chuyển phần tiền liên quan từ `ORDER_REVIEW_HOLD` sang `DISPUTE_HOLD` khi có chứng cứ, kể cả khi hệ thống chưa tự đạt ngưỡng.
- **Finance/Admin:** có thể gia hạn hold (mã nguyên nhân, người phê duyệt, `review_by`) hoặc giải phóng sớm trước `hold_until` khi tranh chấp đã đóng hoặc chủ account được xác định không có lỗi. Giải phóng sớm và các khoản vượt ngưỡng cấu hình cần hai người duyệt.
- Mọi thao tác đi qua bút toán bù, không có API rút trực tiếp từ các trạng thái hold, không sửa/xóa bút toán cũ.

**Tiêu chí nghiệm thu:**
- Không có đường dẫn API nào cho phép rút `ORDER_REVIEW_HOLD`/`DISPUTE_HOLD`.
- Tiền được giải phóng tự động khi thời gian hold hết và không có lý do giữ còn hiệu lực.
- Tranh chấp chỉ khóa đúng khoản tiền liên quan hoặc giới hạn dự phòng phù hợp.
- Có thể truy vết từ mỗi khoản tiền đến order, dispute, policy version và PSP transaction.
- Dashboard phân biệt thời gian review với thời gian chuyển tiền.
- Có audit log cho mọi lần hold, gia hạn hold, quyết định dispute, giải phóng và payout.
- Kiểm thử race condition: payout vừa bắt đầu cùng lúc khi mở dispute phải không gây trả tiền trùng hoặc bỏ qua hold.
- Số dư rút được của chủ account không bao giờ bao gồm tiền cọc chưa được phân xử khấu trừ; mọi bút toán cọc truy vết được về đúng một `order_id`.


---

## UC-42 — Can thiệp thủ công trên các quy trình tự động (human override)

**Mục tiêu:** bảo đảm mọi quy trình tự động trong RentGG có điểm dừng và đường can thiệp thủ công khi phát sinh tình huống ngoài dự kiến, đồng thời can thiệp được kiểm soát, truy vết và không trở thành lỗ hổng.

**Tác nhân:** Chủ account, nhân viên hỗ trợ/phân xử, Admin, Finance, hệ thống RentGG.

**Tiền điều kiện:** có quy trình tự động đang chạy hoặc vừa kết thúc (nhận đơn, bàn giao, rotation, kết thúc phiên, giữ/hoàn cọc, hold/payout, kiểm duyệt, khuyến mại); người can thiệp có vai trò phù hợp.

**Luồng chính:**
1. Người có quyền mở đối tượng cần can thiệp (đơn, listing, account, khoản tiền, hồ sơ tranh chấp) và xem trạng thái tự động hiện tại.
2. Hệ thống hiển thị các hành động thủ công được phép tại trạng thái đó, kèm hậu quả (tiền, lịch, trạng thái account, thông báo).
3. Người can thiệp chọn hành động, nhập `reason_code` và mô tả, chọn phạm vi (một đơn/một listing/một chủ account) và thời hạn nếu là tạm thời.
4. Với hành động thuộc nhóm hai người duyệt, hệ thống chuyển cho người phê duyệt thứ hai; không cho tự duyệt thao tác của chính mình.
5. Hệ thống thực hiện qua workflow/API có kiểm tra điều kiện, không sửa trạng thái trực tiếp trong cơ sở dữ liệu.
6. Hệ thống ghi audit log (người thực hiện, vai trò, thời điểm, trạng thái trước/sau, lý do, đơn liên quan), thông báo bên liên quan và nêu cách kháng nghị.
7. Khi override hết hạn hoặc được gỡ, quy trình tự động tiếp tục từ trạng thái hợp lệ; override khẩn cấp phải được xem xét lại trong thời hạn cấu hình.

**Ma trận can thiệp (O = chủ account, S = hỗ trợ, A = admin, F = Finance):**

| Quy trình tự động | Can thiệp thủ công | Vai trò | Hai người duyệt |
|---|---|---|---|
| Nhận đơn `AUTO_ACCEPT` (UC-38) | Chuyển `MANUAL_APPROVAL`, tắt/tạm khóa `AUTO_ACCEPT`, tạm giữ bàn giao | O, S, A | Không |
| Rotation mật khẩu (UC-39) | Chủ account thực hiện rotation/thu hồi; nhắc chủ account; hủy sớm đơn kế tiếp khi account không kịp sẵn sàng; gỡ tín hiệu `OWNER_FAULT_NOT_READY` do lỗi hệ thống | O, S, A | Không |
| Khôi phục account từ `SUSPENDED_SECURITY_REVIEW` (UC-39, UC-43) | Đưa về `AVAILABLE` sau rotation và xác nhận lại thông tin khôi phục (`ACCOUNT_SECURITY_RESTORE`) | S, A | Có |
| Hết giờ thuê (UC-37) | Ân hạn đặc biệt, kết thúc đơn sớm + chặn xem credentials, yêu cầu chủ đổi mật khẩu | S, O | Không |
| Bàn giao credentials (UC-08) | Tạm chặn xem credentials của một đơn khi nghi ngờ bảo mật; yêu cầu chủ hỗ trợ OTP | S, A | Không |
| Cọc (UC-25, UC-27) | Chuyển cọc sang `DISPUTED` khi có bằng chứng; gia hạn `deadline_at`; xử lý hoàn thất bại | S, F | Có, với điều chỉnh sau quyết định |
| Chiếm account (UC-43) | Kết thúc đơn ngay, `RESTRICTED` người thuê, `SUSPENDED_SECURITY_REVIEW` account; gỡ `RESTRICTED` sau phân xử | S, A | Có, khi khôi phục account |
| Xác minh thông tin khôi phục (UC-44) | Xác minh/từ chối; yêu cầu xác minh lại; tạm ngừng account | A | Không (khôi phục từ `SUSPENDED_SECURITY_REVIEW` thì Có) |
| Hold/payout (UC-41, UC-36) | Chuyển sang dispute hold, gia hạn hoặc giải phóng sớm hold | S, F, A | Có, với giải phóng sớm hoặc vượt ngưỡng |
| Báo cáo cheat/phá hoại (UC-11, UC-12) | Xem xét lại báo cáo, gỡ biện pháp tạm thời nếu phát hiện nhầm, kháng nghị | S, A | Không |
| Điểm uy tín/giới hạn tự động (UC-14) | Gỡ hoặc điều chỉnh giới hạn sau khi xem xét mẫu, lý do, kết quả kháng nghị | A | Có, với đình chỉ/khóa vĩnh viễn |
| Kiểm duyệt tin/trạng thái game (UC-03, UC-05) | Duyệt, gỡ, tạm ẩn thủ công; rà soát lại trạng thái game | A | Có, khi đổi sang `ALLOWED` từ `PROHIBITED`/`SUSPENDED` |
| Chống lạm dụng coupon (UC-32) | Tạm dừng hoặc cấp lại phần thưởng | S, A | Không |
| Ledger (UC-24) | Chỉ bút toán bù có lý do | F | Có |

**Quy tắc nghiệp vụ:**
1. Mọi override phải có `reason_code`, người thực hiện, phạm vi, thời điểm và nằm trong audit log không xóa được.
2. Override tạm thời phải có `expires_at` hoặc `review_by`; không kéo dài vô hạn nếu không có người phê duyệt và lý do mới.
3. Hành động tài chính, giải phóng hold, đưa account từ trạng thái lỗi bảo mật về khả dụng, đổi trạng thái game sang `ALLOWED` và đình chỉ/khóa vĩnh viễn thuộc nhóm hai người duyệt.
4. **Không được override để:** bỏ qua MFA/OTP hoặc cơ chế bảo mật của nhà phát hành; xem mật khẩu dạng văn bản thuần; rút tiền từ trạng thái hold bằng API thường; sửa hoặc xóa bút toán; mở nhận đơn cho game `PROHIBITED`/`SUSPENDED`; bỏ qua kiểm tra độ tuổi; tạo hai phiên thuê đồng thời cho một account; dùng lại, gộp hoặc chuyển cọc giữa các đơn; đăng listing cho GameAccount chưa xác minh thông tin khôi phục.
5. Biện pháp khẩn cấp (dừng phiên, tạm khóa listing) là tạm thời và không phải kết luận cuối; người bị ảnh hưởng được thông báo (trừ khi gây rủi ro bảo mật) và có quyền kháng nghị.
6. Override không thay thế phân xử có bằng chứng (UC-13/UC-27) và không được dùng để né quy trình thanh toán, hoàn tiền hoặc chế tài đã công bố.
7. Hệ thống theo dõi tỷ lệ override theo quy trình để phát hiện quy tắc tự động sai hoặc lạm dụng quyền thủ công; tỷ lệ cao bất thường phải được rà soát.
8. Nhân viên chỉ thấy dữ liệu cần thiết theo vai trò; thao tác của nhân viên cũng nằm trong phạm vi kiểm toán.

**Tiêu chí nghiệm thu:**
- Mỗi quy trình tự động trong ma trận có ít nhất một đường can thiệp thủ công hoạt động và được kiểm thử.
- Mọi override đều tạo audit log đầy đủ và thông báo đúng bên.
- Không thể tự duyệt thao tác thuộc nhóm hai người duyệt.
- Các hành động trong danh sách "không được override" bị từ chối ở cả giao diện lẫn API.
- Override có thời hạn tự hết hiệu lực và quy trình tự động tiếp tục đúng trạng thái.
- Có kiểm thử race condition: override xảy ra đồng thời với một bước tự động (ví dụ hủy đơn cùng lúc payout, rotation cùng lúc nhận đơn) không gây trạng thái sai hoặc trả tiền trùng.


---

## UC-43 — Renter chiếm account trong lúc thuê (đổi mật khẩu/thông tin khôi phục)

**Mục tiêu:** xử lý khẩn cấp khi người thuê đổi mật khẩu, email/SĐT khôi phục hoặc 2FA của account đang thuê (hoặc có dấu hiệu chiếm đoạt), để chủ account lấy lại quyền kiểm soát nhanh nhất, chặn người thuê tiếp tục giao dịch và bồi thường cho chủ account bằng cọc của chính đơn đó cùng yêu cầu bồi thường phần vượt.

**Tác nhân:** Chủ account (người báo), người thuê (bên bị cáo buộc), nhân viên hỗ trợ/phân xử, Admin (người duyệt thứ hai), hệ thống RentGG.

**Tiền điều kiện:**
- Đơn ở `IN_RENTAL`, hoặc đã `ENDED` nhưng cọc còn trong thời gian giữ (`POST_RENTAL_HOLD`, trước `hold_until`).
- Chủ account đã cam kết và được xác minh nắm giữ thông tin khôi phục (UC-44) — đây là cơ sở để chủ account tự lấy lại account qua quy trình khôi phục chính thức của nhà phát hành.

**Luồng chính:**
1. Chủ account phát hiện không đăng nhập được hoặc nhận thông báo từ game về việc đổi mật khẩu/email/SĐT/2FA, chọn **"Báo chiếm account (khẩn)"** trên đơn, mô tả và tải bằng chứng (thông báo của game, ảnh chụp, thời điểm).
2. Hệ thống tạo Dispute loại `ACCOUNT_TAKEOVER` (`OPEN`, ưu tiên khẩn, `deadline_at` ngắn theo cấu hình) và thực hiện ngay các biện pháp tạm thời:
   - Nếu đơn đang `IN_RENTAL`: **đơn chuyển `ENDED` ngay** (ghi `actual_end_at`, lý do `ACCOUNT_TAKEOVER`), chấm dứt quyền xem credentials.
   - **Cọc của đơn chuyển `DISPUTED`** (từ `HELD` hoặc `POST_RENTAL_HOLD`), loại khỏi job hoàn cọc tự động.
   - **GameAccount chuyển `SUSPENDED_SECURITY_REVIEW`**; listing không nhận đơn mới; các đơn tương lai đã xác nhận trên GameAccount này bị hủy với `cancel_reason = ACCOUNT_SECURITY_HOLD` và hoàn 100% tiền thuê + cọc cho người thuê của các đơn đó (không ghi tín hiệu lạm dụng cho chủ account).
   - **Người thuê chuyển `status = RESTRICTED`**: không tạo được đơn mới cho đến khi phân xử xong; các đơn khác của người thuê đang chạy được hỗ trợ xem xét riêng.
3. Hệ thống thông báo người thuê về cáo buộc, các biện pháp tạm thời và thời hạn phản hồi; yêu cầu người thuê trả lại quyền kiểm soát (cung cấp mật khẩu đã đổi qua kênh hỗ trợ, không lưu lâu dài) nếu có.
4. Chủ account dùng email/SĐT/2FA mình nắm giữ để khôi phục account qua quy trình chính thức của nhà phát hành; RentGG hướng dẫn nhưng không can thiệp vào hệ thống của nhà phát hành, không vượt MFA.
5. Nhân viên phân xử thu thập bằng chứng: audit xem credentials, thời điểm đơn, thông báo của game, phản hồi của người thuê, lịch sử tín hiệu của hai bên.
6. Nhân viên quyết định với `reason_code`:
   - **Xác nhận chiếm account:** khấu trừ cọc theo thiệt hại đã chứng minh, tối đa toàn bộ cọc (bút toán `DEPOSIT_DEDUCTED` → chủ account; cọc `SETTLED` nếu khấu trừ hết); giữ hoặc nâng hạn chế người thuê (`RESTRICTED` → `SUSPENDED` với vi phạm nghiêm trọng, cần hai người duyệt theo UC-14); ghi nhận **yêu cầu bồi thường phần thiệt hại vượt cọc** đối với người thuê theo căn cứ hợp lệ, không hứa chắc chắn thu hồi được.
   - **Không xác nhận / do chủ account hoặc bên thứ ba:** hoàn cọc đầy đủ cho người thuê, gỡ `RESTRICTED`, ghi tín hiệu báo cáo sai cho chủ account nếu có căn cứ (UC-14).
7. **Khôi phục account:** sau khi chủ account lấy lại quyền kiểm soát, rotation mật khẩu (UC-39) và xác nhận lại thông tin khôi phục (UC-44), hỗ trợ tạo ApprovalRequest `ACCOUNT_SECURITY_RESTORE`; một người duyệt khác người yêu cầu phê duyệt → GameAccount về `AVAILABLE`.
8. Hai bên nhận thông báo kết quả, số tiền, căn cứ và quyền kháng nghị.

**Ngoại lệ:**
- **Báo sau `hold_until` của cọc:** không chặn được cọc đã hoàn; vẫn tiếp nhận để `SUSPENDED_SECURITY_REVIEW` account, `RESTRICTED` người thuê nếu có bằng chứng mạnh, và ghi yêu cầu bồi thường; không lấy cọc của đơn khác.
- **Chủ account không lấy lại được account vì đã mất thông tin khôi phục:** vi phạm cam kết R2; vẫn phân xử trách nhiệm người thuê nhưng ghi tín hiệu cho chủ account (UC-14, UC-44); account giữ `SUSPENDED_SECURITY_REVIEW`.
- **Người thuê tự trả lại quyền kiểm soát nhanh và không có thiệt hại khác:** vẫn ghi nhận vi phạm; mức khấu trừ cọc theo thiệt hại thực tế và quyết định của phân xử.
- **Báo cáo giả để giữ cọc:** bác khiếu nại, hoàn cọc người thuê, xử lý chủ account theo UC-14.
- **Người thuê có đơn khác đang giữ cọc:** cọc các đơn đó không bị dùng để bù thiệt hại của đơn này (UC-25).

**Quy tắc nghiệp vụ:**
1. `ACCOUNT_TAKEOVER` mở được trong lúc `IN_RENTAL` hoặc trong thời gian giữ cọc; mở trong `IN_RENTAL` thì đơn `ENDED` ngay.
2. Biện pháp tạm thời (kết thúc đơn, cọc `DISPUTED`, account `SUSPENDED_SECURITY_REVIEW`, người thuê `RESTRICTED`) không phải kết luận cuối; người thuê có quyền phản hồi.
3. Chỉ khấu trừ cọc của chính đơn bị khiếu nại, không vượt số cọc; phần vượt là yêu cầu bồi thường riêng.
4. Khôi phục GameAccount từ `SUSPENDED_SECURITY_REVIEW` về `AVAILABLE` bắt buộc **hai người duyệt** (`ACCOUNT_SECURITY_RESTORE`), sau rotation và xác nhận lại thông tin khôi phục.
5. RentGG không vượt MFA/quy trình khôi phục của nhà phát hành; nhân viên không xem mật khẩu cũ hay mới.
6. Các đơn tương lai bị hủy do account bị tạm ngừng không tính là lỗi của chủ account.

**Can thiệp thủ công:** hỗ trợ có thể kích hoạt các biện pháp tạm thời thay chủ account khi có bằng chứng (ví dụ người thuê kế tiếp báo không đăng nhập được do thông tin khôi phục bị đổi); gỡ `RESTRICTED` sau phân xử; khôi phục account cần hai người duyệt (UC-42).

**Tiêu chí nghiệm thu:**
- Báo `ACCOUNT_TAKEOVER` khi `IN_RENTAL` → trong cùng một giao dịch: đơn `ENDED`, cọc `DISPUTED`, GameAccount `SUSPENDED_SECURITY_REVIEW`, người thuê `RESTRICTED`, quyền xem credentials chấm dứt.
- Người thuê `RESTRICTED` không tạo được đơn mới.
- Các đơn tương lai trên account bị hủy và hoàn 100% tiền thuê + cọc, không ghi tín hiệu cho chủ account.
- Khấu trừ không vượt số cọc của đơn; không chạm cọc các đơn khác.
- Không thể đưa account về `AVAILABLE` khi chưa có hai người duyệt khác nhau.
- Mọi bước có audit log và thông báo đúng bên.

---

## UC-44 — Xác minh quyền kiểm soát thông tin khôi phục của chủ account

**Mục tiêu:** bảo đảm (theo R2) chủ account luôn nắm giữ email khôi phục, số điện thoại và 2FA/xác thực của account game, người thuê không bao giờ có các thông tin này, để chủ account có thể lấy lại account khi có sự cố và việc rotation mật khẩu luôn khả thi.

**Tác nhân:** Chủ account, Admin/kiểm duyệt, hệ thống RentGG.

**Tiền điều kiện:**
- Chủ account đủ 18 tuổi, `kyc_status = VERIFIED`.
- Game ở trạng thái `ALLOWED`.

**Luồng chính:**
1. Khi tạo GameAccount, chủ account nhập game, server/region, username (hiển thị che bớt `login_hint`) và mật khẩu (mã hóa thành `CredentialVersion` đầu tiên).
2. Chủ account đọc giải thích và **cam kết từng mục**: `recovery_email_owned` (tôi kiểm soát email khôi phục), `recovery_phone_owned` (tôi kiểm soát số điện thoại khôi phục), `two_factor_owned` (tôi kiểm soát 2FA/xác thực, hoặc game không có 2FA); đồng ý không chia sẻ các thông tin này với người thuê. Thiếu bất kỳ cam kết nào → không tạo được GameAccount.
3. Hệ thống lưu `attested_at` và chuyển GameAccount sang `PENDING_VERIFICATION`; GameAccount chưa dùng được cho listing.
4. Admin xem xét hồ sơ: cam kết, bằng chứng chủ account cung cấp ở mức cần thiết (ví dụ ảnh chụp trang bảo mật của account đã che phần nhạy cảm), lịch sử chủ account, dấu hiệu account mua lại/chiếm đoạt.
5. Admin xác minh (`POST /admin/game-accounts/{id}/verify-recovery`): hệ thống ghi `recovery_verified_by`, `recovery_verified_at` và chuyển GameAccount `PENDING_VERIFICATION` → `AVAILABLE`. Chủ account được thông báo và có thể tạo listing (UC-04).
6. Nếu không đạt, admin từ chối với lý do; GameAccount giữ `PENDING_VERIFICATION`, chủ account có thể bổ sung và gửi lại.
7. **Kiểm tra lại định kỳ:** theo chu kỳ cấu hình (ví dụ mỗi 90 ngày, hoặc sớm hơn với `risk_tier = HIGH`), hệ thống yêu cầu chủ account xác nhận lại cam kết; không xác nhận trong thời hạn → listing tạm dừng nhận đơn mới cho đến khi xác nhận (đơn đã xác nhận vẫn giữ).
8. **Kiểm tra lại khi có sự cố:** sau `ACCOUNT_TAKEOVER` (UC-43), khiếu nại liên quan đăng nhập lặp lại, thay đổi thông tin khôi phục được báo cáo, hoặc tín hiệu bất thường, admin yêu cầu xác minh lại; GameAccount không về `AVAILABLE` trước khi xác minh lại xong.

**Ngoại lệ:**
- Chủ account không thể cam kết một mục (ví dụ email khôi phục thuộc người khác) → không được cho thuê account đó.
- Nghi account mua bán/chiếm đoạt hoặc không có quyền cung cấp → từ chối, có thể chuyển kiểm tra UC-04/UC-05.
- Chủ account báo đã mất quyền kiểm soát thông tin khôi phục → GameAccount chuyển `SUSPENDED_SECURITY_REVIEW`, listing tạm dừng, các đơn tương lai được hủy và hoàn 100%.
- Game thay đổi cơ chế bảo mật (ví dụ bắt buộc OTP) → admin cập nhật `handover_mode` của game và yêu cầu xác minh lại nếu cần.

**Quy tắc nghiệp vụ:**
1. Không GameAccount nào được đăng listing khi chưa `AVAILABLE` sau xác minh thông tin khôi phục.
2. Thông tin khôi phục không bao giờ là một phần của bàn giao (UC-08), không xuất hiện trong mô tả listing, chat hay ticket.
3. RentGG không lưu email/SĐT khôi phục hay mã 2FA của account game; chỉ lưu cờ cam kết, thời điểm và người xác minh. Bằng chứng (nếu có) được che phần nhạy cảm và lưu tối thiểu.
4. Cam kết sai sự thật hoặc mất quyền kiểm soát dẫn đến sự cố là tín hiệu lạm dụng chủ account đã xác minh (UC-14).
5. Khôi phục GameAccount từ `SUSPENDED_SECURITY_REVIEW` cần hai người duyệt (UC-39, UC-43).

**Can thiệp thủ công:** admin có thể yêu cầu xác minh lại bất kỳ lúc nào có lý do; tạm ngừng GameAccount; mọi quyết định xác minh/từ chối ghi audit log với `reason_code` (UC-42).

**Tiêu chí nghiệm thu:**
- Không tạo được GameAccount khi thiếu một trong ba cam kết; GameAccount mới luôn ở `PENDING_VERIFICATION`.
- Không gửi duyệt được listing cho GameAccount `PENDING_VERIFICATION`.
- Admin xác minh ghi `recovery_verified_by`/`recovery_verified_at` và chuyển `AVAILABLE`; người xác minh được lưu trong audit.
- Hết hạn xác nhận lại định kỳ → listing không nhận đơn mới; xác nhận lại → nhận đơn bình thường.
- Không có trường nào lưu email/SĐT khôi phục hoặc mã 2FA của account game.

---

# Phụ lục — Trạng thái chuẩn

Tên trạng thái dưới đây là chuẩn chung cho tài liệu và backend (theo `RentGG_Decisions.md`). Các tên khác xuất hiện trong phần tham chiếu mô hình Trung Quốc chỉ mang tính mô tả.

**Đơn thuê (Order.status):**
| Trạng thái | Ý nghĩa |
|---|---|
| `PENDING_PAYMENT` | Đã tạo đơn, giữ chỗ 15 phút chờ thanh toán tiền thuê + cọc. |
| `AWAITING_OWNER_APPROVAL` | Đã thanh toán, listing không bật `AUTO_ACCEPT`; chủ account duyệt trong 30 phút. |
| `CONFIRMED` | Đã thanh toán và được chấp nhận; chờ đến `start_at`. |
| `IN_RENTAL` | Đang thuê; GameAccount `IN_USE`; người thuê xem được credentials. |
| `ENDED` | Đã kết thúc (đến `block_end_at` hoặc kết thúc sớm do `ACCOUNT_TAKEOVER`); khiếu nại sau đó gắn với Dispute, đơn vẫn `ENDED`. |
| `CANCELLED` | Hủy trước `start_at`, kèm `cancel_reason`: `RENTER_CANCEL`, `OWNER_CANCEL`, `OWNER_FAULT_NOT_READY`, `ACCOUNT_SECURITY_HOLD`, `PLATFORM_CANCEL`, `FORCE_MAJEURE`. |
| `EXPIRED` | Hết thời gian thanh toán; cọc `VOID`. |
| `REJECTED` | Chủ account từ chối hoặc quá hạn duyệt; hoàn 100% tiền thuê + cọc. |

**Tiền cọc (Deposit.status):** `PENDING_PAYMENT` → `HELD` → `POST_RENTAL_HOLD` → `REFUND_PENDING` → `REFUNDED`; nhánh `HELD`/`POST_RENTAL_HOLD` → `DISPUTED` → `REFUND_PENDING` (hoàn toàn bộ hoặc phần còn lại) hoặc `SETTLED` (khấu trừ toàn bộ); hủy trước khi thuê → `REFUND_PENDING` ngay; hết hạn thanh toán → `VOID`.

**GameAccount (security_state):** `PENDING_VERIFICATION`, `AVAILABLE`, `IN_USE`, `AWAITING_RECLAIM`, `ROTATION_PENDING`, `ROTATION_OVERDUE`, `SUSPENDED_SECURITY_REVIEW`.

**Khiếu nại (Dispute):**
- Trạng thái: `OPEN`, `RESOLVED`, `REJECTED`.
- Loại: `OWNER_DAMAGE` (chủ account, nhắm vào cọc), `ACCOUNT_TAKEOVER` (chủ account, khẩn, nhắm vào cọc), `RENTER_LOGIN_FAILED` và `RENTER_NOT_AS_DESCRIBED` (người thuê, nhắm vào tiền thuê/doanh thu chủ account).

**Tham chiếu nhanh khác:** Listing `DRAFT`, `PENDING_REVIEW`, `ACTIVE`, `PAUSED`, `REJECTED`, `SUSPENDED`; `end_mode` `FORCE_AT_END`, `GRACE_FOR_CURRENT_MATCH`, `STAY_ONLINE_UNTIL_RECLAIM`; `handover_mode` `STATIC_CREDENTIAL`, `OWNER_ASSISTED_OTP`; User `ACTIVE`, `RESTRICTED`, `SUSPENDED`; CredentialVersion `ACTIVE`, `RETIRED`; Withdrawal `REQUESTED`, `PAID`, `FAILED`; ApprovalRequest `PENDING`, `APPROVED`, `REJECTED`, `EXECUTED`.
