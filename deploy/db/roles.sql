-- Vai trò cho ứng dụng và migration (Dev Guide §2.2, System Design §5.4, §14.3).
-- Idempotent. Không có mật khẩu và không có tên database: mật khẩu do init-roles.sh đặt, database lấy từ current_database().
--   rentgg_migrator: chạy Flyway. Sở hữu các bảng nó tạo ra.
--   rentgg_app:      chạy ứng dụng. Chỉ có DML trên bảng và quyền dùng sequence; không TRUNCATE, DROP, CREATE, ALTER.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rentgg_migrator') THEN
        CREATE ROLE rentgg_migrator LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rentgg_app') THEN
        CREATE ROLE rentgg_app LOGIN;
    END IF;
END
$$;

-- Migration: tạo bảng trong schema public; V1 cần extension btree_gist (trusted, cần CREATE trên database).
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO rentgg_migrator', current_database());
END
$$;
GRANT USAGE, CREATE ON SCHEMA public TO rentgg_migrator;

-- Ứng dụng: chỉ đọc/ghi dữ liệu.
GRANT USAGE ON SCHEMA public TO rentgg_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rentgg_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rentgg_app;

-- Bảng và sequence mà rentgg_migrator tạo sau này cũng được cấp tự động cho ứng dụng.
ALTER DEFAULT PRIVILEGES FOR ROLE rentgg_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rentgg_app;
ALTER DEFAULT PRIVILEGES FOR ROLE rentgg_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO rentgg_app;
