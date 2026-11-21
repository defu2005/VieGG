#!/bin/sh
# Khôi phục DB từ một bản backup trên R2. CHẠY TRÊN VPS, trong thư mục deploy/.
#   ./restore.sh                      liệt kê các bản backup
#   ./restore.sh rentgg-20261101-030000.dump   khôi phục bản đó (GHI ĐÈ dữ liệu hiện tại)
# Sau khi khôi phục: chạy truy vấn đối soát ledger (System Design §16.3) trước khi mở lại giao dịch.
set -eu
cd "$(dirname "$0")"
DC="docker compose -f docker-compose.prod.yml --env-file .env.prod"

if [ $# -eq 0 ]; then
  $DC run --rm --entrypoint sh backup -c '
    export RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare RCLONE_CONFIG_R2_ENDPOINT="$BACKUP_S3_ENDPOINT" \
           RCLONE_CONFIG_R2_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY"
    rclone lsl "r2:$BACKUP_S3_BUCKET/db/"'
  exit 0
fi

printf "Khôi phục %s sẽ GHI ĐÈ toàn bộ DB hiện tại. Gõ YES để tiếp tục: " "$1"
read -r answer
[ "$answer" = "YES" ] || { echo "Đã huỷ."; exit 1; }

# App luôn được bật lại, kể cả khi khôi phục lỗi.
trap '$DC start app >/dev/null' EXIT
$DC stop app
# Tải bản backup về trước, kiểm tra hợp lệ, rồi mới đụng tới DB. -1: tất cả trong một transaction, lỗi thì không đổi gì.
$DC run --rm -e DUMP="$1" --entrypoint sh backup -c '
  set -eu
  export RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare RCLONE_CONFIG_R2_ENDPOINT="$BACKUP_S3_ENDPOINT" \
         RCLONE_CONFIG_R2_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY"
  rclone copyto "r2:$BACKUP_S3_BUCKET/db/$DUMP" /tmp/restore.dump
  pg_restore --list /tmp/restore.dump >/dev/null
  # --role: bảng và sequence thuộc rentgg_migrator (không phải superuser), để Flyway và quyền của rentgg_app vẫn đúng.
  pg_restore --clean --if-exists --no-owner --role=rentgg_migrator -1 -d rentgg /tmp/restore.dump'
echo "Xong. Kiểm tra ứng dụng và đối soát ledger trước khi mở lại giao dịch."
