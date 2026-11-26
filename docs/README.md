# Tài liệu RentGG — mục lục

Mục này liệt kê mọi tài liệu trong `docs/`, mục đích, và tài liệu nào là nguồn đúng cho việc gì.

## Quy tắc nguồn đúng

- **Hành vi thật của hệ thống:** mã nguồn và migration (`backend/src/main/...`, `backend/src/main/resources/db/migration/`) là nguồn đúng. Khi tài liệu khác mã, tài liệu là bản cần sửa.
- **Ý định thiết kế:** `RentGG_System_Design_v1.2.md`. Đã có mâu thuẫn với code ở mục 9 (xem bên dưới).
- **Nghiệp vụ theo use case:** `RentGG_Use_Cases_China_Model_v8.md`.
- Tài liệu trạng thái (`*_Status.md`, `Implementation_Status`) ghi thời điểm rà soát. Đọc kèm ngày ghi trong file.

## Danh mục

| Tài liệu | Mục đích | Trạng thái |
|---|---|---|
| `RentGG_System_Design_v1.2.md` | Thiết kế hệ thống: kiến trúc, mô hình dữ liệu, máy trạng thái, ledger, job, API (tóm tắt), bảo mật, observability, triển khai, kiểm thử | Thiết kế. Có lệch với code (mục dưới) |
| `RentGG_Use_Cases_China_Model_v8.md` | Đặc tả 44 use case và danh mục trạng thái chuẩn | Nghiệp vụ |
| `RentGG_Implementation_Status.md` | Báo cáo triển khai backend Phase 0 và việc còn thiếu | Trạng thái, rà soát 2026-11-21 |
| `RentGG_Frontend_Status.md` | Tình trạng frontend | Trạng thái |
| `RentGG_iOS_Status.md` | Tình trạng app iOS | Trạng thái |
| `RentGG_Validation_Checklist.md` | Checklist kiểm chứng trước khi ra mắt, việc nghiệp vụ cần người làm (PSP, pháp lý, cấu hình game) | Việc của người, không phải của agent |
| `RentGG_Dev_Guide.md` | Các việc dev theo mục, quy tắc cho agent cloud | Hướng dẫn việc đang làm |
| `RentGG_Cloud_Brief.md` | Brief cho agent cloud | Hướng dẫn |
| `RentGG_Dev_Seed.md` | Dữ liệu seed cho môi trường dev | Dev |
| `RentGG_Production_Deploy.md` | Dựng production: VPS, DNS, Cloudflare, lệnh thường dùng, vai trò DB, MFA admin đầu tiên | Hướng dẫn triển khai |
| `RentGG_Operations_Runbook.md` | Vận hành: điều kiện khởi động, job, kiểm tra khi có triệu chứng, sao lưu và khôi phục, chưa có trong repo | Vận hành, viết từ mã nguồn |
| `RentGG_Staff_Guide.md` | Hướng dẫn nhân viên hỗ trợ, quản trị, tài chính: vai trò, quyền, màn hình, phê duyệt | Nhân viên, viết từ mã nguồn |
| `../README.md` (ngoài `docs/`) | Chạy nhanh, cấu trúc thư mục, kiểm thử | Một phần đã cũ, xem mục 2, 4, 5 bên dưới |

## Mâu thuẫn đã kiểm chứng

Các mục dưới đây đã đối chiếu với file trong repo. Chưa sửa tài liệu nào trong lượt này.

1. **Phạm vi migration.** `CLAUDE.md` nói không sửa V1–V30 và migration mới đánh số từ V31. Repo đã có V1–V37 (`backend/src/main/resources/db/migration/`). Migration kế tiếp là V38. `docs/RentGG_Production_Deploy.md` mục B1b vẫn nhắc "sau khi triển khai V31" như việc sắp tới.
2. **Số lượng test.** `README.md` ghi "31 test". `docs/RentGG_Production_Deploy.md` mục B1a ghi "full suite 226/0/0". Repo có 64 file test (`*IT.java`, `*Test.java`). Chưa chạy lại test để xác định số đúng.
3. **Job định kỳ.** `RentGG_System_Design_v1.2.md` §9 liệt kê 9 job. `backend/src/main/java/com/rentgg/jobs/Jobs.java` có 13 job. Xem `RentGG_Operations_Runbook.md` mục 2.
4. **Xác thực hai bước cho nhân viên.** `README.md` (mục "Chưa làm") ghi "MFA cho staff" chưa làm. Migration `V31__staff_mfa.sql` và `docs/RentGG_Production_Deploy.md` mục B1b cho thấy tính năng đã có.
5. **README gốc có phần cũ.** `README.md` nhắc migration `V1..V7` (repo có V1–V37) và "31 test" (xem mục 2).
6. **Cổng frontend.** `README.md` ghi `http://localhost:5173` (mặc định của `frontend/vite.config.ts`). `CLAUDE.md` và `.claude/launch.json` dùng `5273` với `--port 5273`. Không mâu thuẫn về hành vi, chỉ khác cấu hình chạy.

## Chưa có

- ADR, changelog và ghi chú quyết định lịch sử: không viết lại. Lý do: không có nguồn đủ để tái tạo lý do quyết định, viết lại sẽ là suy diễn.
- Tài liệu API chi tiết: sẽ có sau khi xuất OpenAPI từ backend dev hoặc có bảng endpoint đối chiếu từ controller.
