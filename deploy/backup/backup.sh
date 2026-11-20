#!/bin/sh
# Sao lưu Postgres lên R2 mỗi ngày lúc BACKUP_HOUR (giờ Việt Nam), xoá bản cũ hơn BACKUP_KEEP_DAYS ngày.
#   backup.sh          chạy vòng lặp hằng ngày (mặc định của container)
#   backup.sh now      sao lưu ngay một lần rồi thoát
# rclone đọc cấu hình remote "r2" từ biến môi trường RCLONE_CONFIG_R2_* (không cần file cấu hình).
set -eu

: "${BACKUP_S3_ENDPOINT:?BACKUP_S3_ENDPOINT is required}"
: "${BACKUP_S3_BUCKET:?BACKUP_S3_BUCKET is required}"
: "${BACKUP_S3_ACCESS_KEY:?BACKUP_S3_ACCESS_KEY is required}"
: "${BACKUP_S3_SECRET_KEY:?BACKUP_S3_SECRET_KEY is required}"
BACKUP_HOUR="${BACKUP_HOUR:-3}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-30}"
export TZ="${BACKUP_TZ:-Asia/Ho_Chi_Minh}"

export RCLONE_CONFIG_R2_TYPE=s3
export RCLONE_CONFIG_R2_PROVIDER=Cloudflare
export RCLONE_CONFIG_R2_ENDPOINT="$BACKUP_S3_ENDPOINT"
export RCLONE_CONFIG_R2_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY"
export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY"
export RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true

backup_once() {
  name="rentgg-$(date +%Y%m%d-%H%M%S).dump"
  echo "[backup] $(date -Iseconds) pg_dump -> r2:$BACKUP_S3_BUCKET/db/$name"
  # Định dạng custom (-Fc) đã nén sẵn, khôi phục bằng pg_restore.
  pg_dump -Fc --no-owner | rclone rcat "r2:$BACKUP_S3_BUCKET/db/$name"
  rclone delete --min-age "${BACKUP_KEEP_DAYS}d" "r2:$BACKUP_S3_BUCKET/db/"
  echo "[backup] done"
}

if [ "${1:-}" = "now" ]; then
  backup_once
  exit 0
fi

while true; do
  now=$(date +%s)
  target=$(date -d "$(date +%Y-%m-%d) $(printf %02d "$BACKUP_HOUR"):00:00" +%s)
  if [ "$target" -le "$now" ]; then
    target=$((target + 86400))
  fi
  echo "[backup] next run in $(( (target - now) / 60 )) minutes"
  sleep $((target - now))
  backup_once || echo "[backup] FAILED at $(date -Iseconds)"
done
