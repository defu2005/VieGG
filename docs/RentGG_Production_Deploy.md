# Triển khai RentGG lên production

Kiến trúc: **1 VPS** (2 vCPU / 4 GB) chạy `deploy/docker-compose.prod.yml` gồm Postgres, backend, Caddy (HTTPS, web tĩnh và `/api` trên cùng tên miền) và container sao lưu. **Cloudflare** đứng trước làm DNS/CDN/chống DDoS. **Cloudflare R2** chứa ảnh/video (bucket `rentgg-media`) và bản sao lưu DB (bucket `rentgg-backup`).

Ưu đãi sinh viên (Azure for Students, GitHub Student Pack…) chỉ dùng cho **dev/staging/test tải**, không dùng cho production thu tiền thật (điều khoản chỉ cho phép mục đích học tập, phi thương mại).

---

## A. Việc bạn phải tự làm (cần tài khoản, giấy tờ hoặc thanh toán)

### A1. Pháp lý và thanh toán (bắt đầu sớm nhất, mất nhiều tuần)
- [ ] Đăng ký **hộ kinh doanh hoặc doanh nghiệp** (cổng thanh toán yêu cầu).
- [ ] Đăng ký **sàn giao dịch TMĐT** với Bộ Công Thương (online.gov.vn). RentGG là nền tảng trung gian nên nhiều khả năng thuộc diện *đăng ký*, không chỉ *thông báo*.
- [ ] Mở tài khoản merchant **thật** ở ít nhất một cổng (payOS là cổng dễ bắt đầu nhất), lấy key production.
- [ ] Soạn **Điều khoản sử dụng** và **Chính sách quyền riêng tư** (Nghị định 13/2023 về bảo vệ dữ liệu cá nhân).
- [ ] Đọc điều khoản của các game được cho thuê tài khoản: có game cấm việc này.

### A2. Tài khoản dịch vụ
- [ ] **Tên miền** `.vn` (hoặc tên miền khác), trỏ nameserver về **Cloudflare** (gói Free).
- [ ] **VPS** 2 vCPU / 4 GB / 40 GB+ SSD, Ubuntu 24.04 (Hetzner, Contabo, Vultr hoặc nhà cung cấp trong nước). Ghi lại IP.
- [ ] Trên Cloudflare, mở **R2** (cần thêm thẻ để kích hoạt, gói free 10 GB) và tạo:
  - bucket `rentgg-media` + API token *Object Read & Write* **chỉ cho bucket này**;
  - bucket `rentgg-backup` + **token riêng** cho bucket này;
  - ghi lại *Account ID* (endpoint là `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`).
- [ ] Apple Developer ($99/năm) và Google Play Console ($25 một lần) nếu phát hành app.

### A3. Trên VPS (một lần)
```bash
# Cài Docker
curl -fsSL https://get.docker.com | sh
# Tường lửa: chỉ SSH, HTTP, HTTPS
ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
# Lấy code
git clone https://github.com/defu2005/VieGG.git ~/rentgg
cd ~/rentgg/deploy && cp .env.prod.example .env.prod && chmod 600 .env.prod
```
- [ ] Điền `.env.prod`: tên miền, email, secret mới sinh (lệnh nằm đầu file), key R2, key cổng thanh toán **production**.
- [ ] **Lưu khóa `RENTGG_CREDENTIAL_KEYS` ở nơi an toàn ngoài VPS** (trình quản lý mật khẩu). Khóa này **không** nằm trong backup DB; mất khóa là mất toàn bộ mật khẩu tài khoản game đã lưu.

### A4. Cloudflare DNS và HTTPS
1. Thêm bản ghi `A  @ → IP VPS` ở chế độ **DNS only (đám mây xám)**.
2. Chạy stack (mục B1). Đợi Caddy lấy chứng chỉ Let's Encrypt (xem `docker compose logs caddy`).
3. Bật **Proxied (đám mây cam)**; *SSL/TLS → Full (strict)*.

### A5. Sau khi chạy
- [ ] Đăng ký tài khoản của bạn trên web, rồi `./make-admin.sh email@cua-ban` và đăng nhập lại.
- [ ] Trong trang quản trị: tạo danh mục game, thiết lập phí.
- [ ] Đặt **IPN/Webhook URL** trong trang merchant của cổng thanh toán: `https://<tên miền>/api/v1/payments/webhook/<vnpay|payos|...>`.
- [ ] Thêm theo dõi uptime miễn phí (UptimeRobot, Better Stack…) cho `https://<tên miền>/`.
- [ ] (Tuỳ chọn) GitHub → Settings → Secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` để bấm triển khai từ tab Actions.
- [ ] Mỗi tháng: thử khôi phục một bản backup trên máy khác (`./restore.sh`).

---

## B. Phần đã chuẩn bị sẵn trong repo

| Thành phần | File |
| --- | --- |
| Stack production | `deploy/docker-compose.prod.yml` |
| HTTPS, web tĩnh, `/api`, chặn `/actuator` `/swagger`, IP thật sau Cloudflare | `deploy/Caddyfile` |
| Build web + Caddy | `deploy/web.Dockerfile` |
| Sao lưu DB hằng ngày lên R2, giữ 30 ngày | `deploy/backup/` |
| Khôi phục (an toàn: kiểm tra file trước, một transaction, luôn bật lại app) | `deploy/restore.sh` |
| Cấp admin đầu tiên | `deploy/make-admin.sh` |
| Mẫu biến môi trường | `deploy/.env.prod.example` |
| Ảnh/video lên R2, dữ liệu cũ tự chuyển dần | `backend/.../shared/media/`, `jobs/media/MediaBackfill.java`, migration `V30` |
| Profile `prod`: tắt Swagger, chặn khởi động nếu thiếu HTTPS/CORS/secret | `application.yml`, `StartupSafetyCheck.java` |
| CI test backend, build web, kiểm tra Caddyfile, triển khai thủ công qua SSH | `.github/workflows/backend.yml`, `web.yml`, `deploy.yml` |

### B1. Lệnh thường dùng (trên VPS, trong `~/rentgg/deploy`)
```bash
DC="docker compose -f docker-compose.prod.yml --env-file .env.prod"
$DC up -d --build            # chạy / cập nhật
$DC ps                       # trạng thái
$DC logs -f --tail 100 app   # log backend
$DC exec backup backup.sh now   # sao lưu ngay
./restore.sh                 # liệt kê bản sao lưu
./restore.sh <file.dump>     # khôi phục
```
Cập nhật code: `git pull && $DC up -d --build` (hoặc bấm workflow **Deploy**).

### B1a. Vai trò cơ sở dữ liệu (Dev Guide §2.2)
- Ứng dụng chạy bằng `rentgg_app` (chỉ DML, không TRUNCATE/DROP/CREATE/ALTER). Flyway chạy bằng `rentgg_migrator`, là chủ sở hữu các bảng. `rentgg` vẫn là superuser của container, dùng cho sao lưu, khôi phục và `make-admin.sh`.
- Mẫu: điền `DB_MIGRATOR_PASSWORD` và `DB_APP_PASSWORD` trong `.env.prod` (khác `DB_PASSWORD`). Định nghĩa vai trò ở `deploy/db/roles.sql`; `deploy/db/init-roles.sh` chạy khi volume Postgres còn trống.
- **Volume đã có dữ liệu** (`init-roles.sh` không chạy lại): làm một lần bằng tay, trên VPS trong `~/rentgg/deploy`:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db \
  psql -v ON_ERROR_STOP=1 -U rentgg -d rentgg -f /opt/rentgg/db/roles.sql
```

Sau đó đặt mật khẩu và chuyển quyền sở hữu bảng và sequence cho `rentgg_migrator`:

```sql
ALTER ROLE rentgg_migrator WITH LOGIN PASSWORD '<DB_MIGRATOR_PASSWORD>';
ALTER ROLE rentgg_app WITH LOGIN PASSWORD '<DB_APP_PASSWORD>';
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE format('ALTER TABLE public.%I OWNER TO rentgg_migrator', r.tablename);
  END LOOP;
  FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' LOOP
    EXECUTE format('ALTER SEQUENCE public.%I OWNER TO rentgg_migrator', r.sequencename);
  END LOOP;
END $$;
```

  Hàm trigger (ví dụ `orders_check_account_blocks`) vẫn thuộc `rentgg`. Migration sau này sửa các hàm đó cần chuyển quyền sở hữu tương tự.
- `restore.sh` khôi phục với `--role=rentgg_migrator`, để bảng sau khôi phục vẫn thuộc migrator. Bản khôi phục cũ (trước thay đổi này) có bảng thuộc `rentgg`: chạy lại lệnh chuyển quyền sở hữu ở trên trước khi khởi động app.
- Kiểm chứng (cục bộ, không Docker): `DbRolesIT` 5/5; full suite 226/0/0; full suite với `rentgg_app` và Flyway bằng `rentgg_migrator` 221/0/0 (lúc đó auth là trust); khởi động prod với `rentgg_app` (xác thực scram) thành công; khôi phục với `--role` cho chủ sở hữu đúng.

### B1b. Xác thực hai bước cho nhân viên (Dev Guide §2.4)
- Biến `RENTGG_STAFF_MFA_REQUIRED` mặc định `true` trên prod và không được tắt (ứng dụng từ chối khởi động nếu tắt).
- **Admin đầu tiên**: sau `./make-admin.sh email`, đăng nhập bằng mật khẩu, mở trang quản trị và làm theo bước "Bật xác thực hai bước" (tạo khoá, nhập mã vào ứng dụng xác thực, xác nhận). Lần đầu chỉ dựa vào mật khẩu.
- **Mất thiết bị xác thực**: reset tay trong database (không có màn hình khôi phục). Xoá dòng `user_totp` của người đó và thu hồi refresh token của họ:

```sql
DELETE FROM user_totp WHERE user_id = '<user-id>';
UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = '<user-id>' AND revoked_at IS NULL;
```

  Lưu ý: access token là JWT không lưu phía server, nên sau khi thu hồi refresh token, token đang dùng vẫn có quyền tới khi hết hạn (tối đa 15 phút). Khởi động lại ứng dụng không cắt được phiên này.
- Sau khi triển khai V31, mọi phiên staff cũ không có claim `mfa` và phải đăng nhập lại kèm mã.

### B2. Lưu trữ ảnh/video
- `RENTGG_MEDIA_STORAGE=s3` (mặc định trên profile `prod`): ảnh mới lên R2, DB chỉ giữ khoá. API không đổi nên web/Android/iOS không phải sửa.
- Dev giữ `db` như cũ.
- Dữ liệu cũ còn trong DB tự chuyển sang R2 khi backend khởi động (`RENTGG_MEDIA_BACKFILL=true`). Xong thì chạy `VACUUM FULL listing_images, dispute_attachments, support_attachments;` để trả lại dung lượng đĩa.

### B3. Kiểm thử trên máy dev (lỗi thời, chưa chạy lại)
> Ghi chú 2026-11-21: mô tả lần chạy cũ (29/10/2026), chưa chạy lại. Chạy lại theo [Dev Guide §5](RentGG_Dev_Guide.md) trước khi dựa vào kết quả này.

Lần chạy cũ (29/10/2026):
Chạy nguyên stack với tên miền `localhost` và S3Mock thay R2: HTTPS, web, chặn `/actuator`, đăng ký/đăng nhập, `make-admin.sh`, upload/tải ảnh qua R2, chuyển dữ liệu cũ, sao lưu và khôi phục (cả trường hợp file sai) đều chạy đúng. **Chưa** thử với R2 thật, Let's Encrypt thật và Cloudflare proxy thật: làm ở bước A4.
