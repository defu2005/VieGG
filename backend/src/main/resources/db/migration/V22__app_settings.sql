-- Platform settings that admins change at runtime (withdrawal limits, banner, site texts...).
-- Which settings exist, their type, default and validation live in code (com.rentgg.settings.domain.Setting);
-- a row only exists for a setting an admin has changed, so a missing row means "use the default".
CREATE TABLE app_settings (
    key        text PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_.]{1,79}$'),
    value      text NOT NULL,
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO permissions (code, resource, action, description) VALUES
    ('setting:read', 'setting', 'read', 'Xem cấu hình hệ thống'),
    ('setting:update', 'setting', 'update', 'Sửa cấu hình hệ thống (rút tiền, banner, nội dung trang...)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', unnest(ARRAY['setting:read', 'setting:update'])
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'FINANCE', 'setting:read'
WHERE EXISTS (SELECT 1 FROM roles WHERE code = 'FINANCE')
ON CONFLICT DO NOTHING;
