#!/bin/sh
# Chạy khi volume Postgres còn trống (lần đầu khởi động container db). Tạo hai vai trò rồi đặt mật khẩu.
# Mật khẩu đi qua biến psql (:'name'), không ghép vào chuỗi SQL.
set -eu
: "${DB_MIGRATOR_PASSWORD:?DB_MIGRATOR_PASSWORD is required}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /opt/rentgg/db/roles.sql
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_pw="$DB_MIGRATOR_PASSWORD" -v app_pw="$DB_APP_PASSWORD" <<'SQL'
ALTER ROLE rentgg_migrator WITH LOGIN PASSWORD :'migrator_pw';
ALTER ROLE rentgg_app WITH LOGIN PASSWORD :'app_pw';
SQL
