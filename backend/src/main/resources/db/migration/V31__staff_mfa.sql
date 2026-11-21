-- Xác thực hai bước cho tài khoản staff (Dev Guide §2.4, System Design §4.2).
-- Phiên đăng nhập cũ không có cờ này: staff phải đăng nhập lại kèm mã TOTP.
ALTER TABLE refresh_tokens ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;

-- Bí mật TOTP được mã hóa bằng CredentialCipher (khóa theo user id). last_used_step chống dùng lại mã.
CREATE TABLE user_totp (
    user_id          uuid PRIMARY KEY REFERENCES users(id),
    secret_key_id    text NOT NULL,
    secret_iv        bytea NOT NULL,
    secret_ct        bytea NOT NULL,
    confirmed_at     timestamptz,
    last_used_step   bigint,
    failed_attempts  integer NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
