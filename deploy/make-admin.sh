#!/bin/sh
# Cấp vai trò ADMIN cho một tài khoản đã đăng ký (prod không có dữ liệu mẫu). CHẠY TRÊN VPS, trong thư mục deploy/.
#   ./make-admin.sh ban@example.com
# Sau đó đăng xuất rồi đăng nhập lại để quyền mới có hiệu lực. Admin tiếp theo nên được cấp từ trang quản trị.
set -eu
cd "$(dirname "$0")"
[ $# -eq 1 ] || { echo "Cách dùng: $0 email"; exit 1; }
EMAIL=$(printf %s "$1" | tr '[:upper:]' '[:lower:]')

docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db \
  psql -v ON_ERROR_STOP=1 -v email="$EMAIL" -U rentgg -d rentgg <<'SQL'
SELECT count(*) = 1 AS found FROM users WHERE email = :'email' \gset
\if :found
  INSERT INTO user_roles (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = :'email' ON CONFLICT DO NOTHING;
  \echo 'Đã cấp ADMIN. Đăng nhập lại để có hiệu lực.'
\else
  \echo 'Không tìm thấy tài khoản này. Đăng ký trên web trước.'
  \quit
\endif
SQL
