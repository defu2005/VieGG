# Tài liệu RentGG — mục lục

Mục này liệt kê mọi tài liệu trong `docs/`, mục đích, và tài liệu nào là nguồn đúng cho việc gì.

## Quy tắc nguồn đúng

- **Hành vi thật của hệ thống:** mã nguồn và migration (`backend/src/main/...`, `backend/src/main/resources/db/migration/`) là nguồn đúng. Khi tài liệu khác mã, tài liệu là bản cần sửa.
- **Ý định thiết kế:** `RentGG_System_Design_v1.2.md`. Có lệch với code ở mục bên dưới.
- **Nghiệp vụ theo use case:** `RentGG_Use_Cases_China_Model_v8.md`.
- Tài liệu trạng thái (`*_Status.md`) ghi ngày rà soát. Đọc kèm ngày đó.

## Danh mục

| Tài liệu | Mục đích | Trạng thái |
|---|---|---|
| `RentGG_System_Design_v1.2.md` | Thiết kế: kiến trúc, mô hình dữ liệu (§5), máy trạng thái (§6), ledger (§7), luồng kỹ thuật (§8), job (§9), outbox (§10), API (§11), bảo mật (§12), cấu hình (§14), triển khai (§17), kiểm thử (§18) | Thiết kế. Có lệch với code ở mục dưới |
| `RentGG_Use_Cases_China_Model_v8.md` | Đặc tả use case và phụ lục trạng thái chuẩn | Nghiệp vụ |
| `RentGG_Implementation_Status.md` | Báo cáo triển khai backend Phase 0 và việc còn thiếu | Trạng thái, rà soát 2026-11-21 |
| `RentGG_Frontend_Status.md` | Tình trạng frontend | Trạng thái, rà soát 2026-11-21 |
| `RentGG_iOS_Status.md` | Tình trạng app iOS | Trạng thái, rà soát 2026-11-21 |
| `RentGG_Validation_Checklist.md` | Checklist trước khi ra mắt, do đội làm: A (kiểm tra theo từng game, trên account thử của đội), B (câu hỏi cho PSP), C (kịch bản phỏng vấn), D (đầu ra) | Việc của người, không phải của agent |
| `RentGG_Dev_Guide.md` | Các việc dev theo mục, quy tắc làm việc (mục 0), mục 7 làm mới tài liệu | Hướng dẫn việc đang làm |
| `RentGG_Cloud_Brief.md` | Brief cho agent cloud | Hướng dẫn |
| `RentGG_Dev_Seed.md` | Dữ liệu seed cho môi trường dev | Dev |
| `RentGG_Production_Deploy.md` | Dựng production: VPS, DNS, Cloudflare, lệnh thường dùng, vai trò DB, MFA admin đầu tiên, trạng thái kiểm thử triển khai | Hướng dẫn triển khai |
| `RentGG_Operations_Runbook.md` | Vận hành: điều kiện khởi động, job định kỳ, kiểm tra khi có triệu chứng, sao lưu và khôi phục | Vận hành, viết từ mã nguồn |
| `RentGG_Staff_Guide.md` | Nhân viên hỗ trợ, quản trị, tài chính: vai trò, quyền, màn hình, phê duyệt kép, thao tác đã kiểm thử tay | Nhân viên, viết từ mã nguồn |
| `RentGG_Data_Dictionary_Delta.md` | Phần bổ sung cho từ điển dữ liệu: bảng ngoài §5, quy ước sổ cái, trạng thái cọc, các cửa sổ thời gian | Đối chiếu với migration và mã, lượt viết tài liệu này |
| `RentGG_API_Reference.md` | Endpoint và quyền, lấy từ khai báo controller và `@PreAuthorize` | Đối chiếu với §11.2; chưa xuất từ springdoc |
| `../SECURITY.md` | Kênh báo lỗ hổng (chưa có), điều đã có trong mã, quy tắc bí mật | Chủ dự án cần điền kênh báo lỗ hổng |
| `../CONTRIBUTING.md` | Nhánh, migration, lệnh test, CI | Lấy từ CLAUDE.md, AGENTS.md và workflow |
| `../backend/README.md` | Cấu trúc module, chạy local, test, Docker | Lấy từ mã và cấu hình |
| `RentGG_Manual_Test_Report.md` | Kết quả kiểm thử tay trên web: 106 hàng, PASS, BLOCKED, NOT_IMPLEMENTED, phát hiện và trạng thái dữ liệu dev | Kết quả một lần chạy, không cập nhật tự động |
| `../README.md` (ngoài `docs/`) | Chạy nhanh, cấu trúc thư mục, kiểm thử | Không có ngày rà soát; nội dung đã đối chiếu với repo |

## Mâu thuẫn đã kiểm chứng

1. **Phạm vi migration.** `CLAUDE.md` và `AGENTS.md` nói không sửa V1–V30 và migration mới đánh số từ V31. Repo đã có V1–V37 (`backend/src/main/resources/db/migration/`). Migration kế tiếp là V38. Hai file đó do chủ dự án quản lý, chưa được sửa trong lượt này.
2. **Job định kỳ.** `RentGG_System_Design_v1.2.md` §9 liệt kê 9 job. `backend/src/main/java/com/rentgg/jobs/Jobs.java` có 13 job. Xem `RentGG_Operations_Runbook.md` mục 2.
3. **Hoàn tiền.** `RentGG_System_Design_v1.2.md` mô tả hoàn tiền qua PSP: gateway có hàm `refund` (mục 3, A3), và trạng thái `REFUND_PENDING` chuyển sang `REFUNDED` khi "PSP hoàn thành công" (mục 6.2). `PaymentService.processRefund` (`backend/src/main/java/com/rentgg/payment/application/PaymentService.java`, khoảng dòng 377–387) ghi refund `SUCCEEDED` với phương thức `wallet` và cộng vào ví RentGG của người trả tiền; không gọi PSP. Chưa rõ đây là chủ đích sản phẩm hay thiếu sót. Cần người dùng quyết định; tài liệu thiết kế và mã chưa khớp cho tới khi quyết định.

## Cập nhật tài liệu trạng thái

`RentGG_Dev_Guide.md` mục 7 đã cập nhật ba file `*_Status.md` ngày 2026-11-21. Lượt viết tài liệu này không sửa ba file đó. Không có mục nào của Dev Guide tương ứng với việc viết tài liệu này, nên không đánh dấu mục nào.

## Chưa có

- ADR, changelog và ghi chú quyết định lịch sử: không viết lại. Lý do: không có nguồn đủ để tái tạo lý do quyết định, viết lại sẽ là suy diễn.
- Xuất OpenAPI từ springdoc: chưa làm, vì backend và PostgreSQL không chạy trong container lượt này. `RentGG_API_Reference.md` lấy từ controller. Đã đối chiếu: 54/54 endpoint trong `RentGG_System_Design_v1.2.md` §11.2 có trong controller; 96 endpoint trong controller không có trong §11.2.
