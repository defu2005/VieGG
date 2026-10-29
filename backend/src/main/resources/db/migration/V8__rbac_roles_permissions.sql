-- RBAC: roles and permissions become data instead of hard-coded names.
-- Permission codes are declared in code (com.rentgg.shared.security.Permission) and synced at startup;
-- this migration seeds them so the 4 original roles keep exactly the access they had before.

CREATE TABLE roles (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    name        text NOT NULL,
    description text,
    is_system   boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    code        text PRIMARY KEY CHECK (code ~ '^[a-z_]+:[A-Za-z_:]+$'),
    resource    text NOT NULL,
    action      text NOT NULL,
    description text NOT NULL
);

CREATE TABLE role_permissions (
    role_code       text NOT NULL REFERENCES roles(code) ON DELETE CASCADE,
    permission_code text NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
    PRIMARY KEY (role_code, permission_code)
);
CREATE INDEX ix_role_permissions_permission ON role_permissions (permission_code);

INSERT INTO roles (code, name, description, is_system) VALUES
    ('USER',    'Người dùng', 'Vai trò mặc định của mọi tài khoản', true),
    ('SUPPORT', 'Hỗ trợ',     'Xử lý khiếu nại, người dùng, kiểm duyệt cơ bản', true),
    ('ADMIN',   'Quản trị',   'Quản trị nền tảng', true),
    ('FINANCE', 'Tài chính',  'Chi trả, đối soát, điều chỉnh sổ cái', true);

-- user_roles.role: replace the hard-coded CHECK with a foreign key to roles.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'user_roles'::regclass AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE user_roles DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE user_roles
    ADD CONSTRAINT fk_user_roles_role FOREIGN KEY (role) REFERENCES roles(code) ON UPDATE CASCADE;

INSERT INTO permissions (code, resource, action, description) VALUES
    ('listing:manage_own', 'listing', 'manage_own', 'Tạo, sửa, gửi duyệt, tạm dừng listing của mình'),
    ('game_account:manage_own', 'game_account', 'manage_own', 'Quản lý tài khoản game của mình'),
    ('order:place', 'order', 'place', 'Tạo đơn thuê và thanh toán'),
    ('order:manage_own', 'order', 'manage_own', 'Duyệt, từ chối, huỷ, bàn giao đơn mình tham gia'),
    ('credential:view_own', 'credential', 'view_own', 'Xem thông tin đăng nhập của đơn mình đang thuê'),
    ('dispute:open', 'dispute', 'open', 'Mở khiếu nại và bổ sung bằng chứng cho đơn của mình'),
    ('wallet:withdraw', 'wallet', 'withdraw', 'Tạo yêu cầu rút tiền'),
    ('game:read_hidden', 'game', 'read_hidden', 'Xem game chưa được phép (REVIEW_REQUIRED, PROHIBITED, ...)'),
    ('game:create', 'game', 'create', 'Tạo game'),
    ('game:update', 'game', 'update', 'Sửa chính sách game'),
    ('game:change_tos', 'game', 'change_tos', 'Đổi trạng thái ToS của game'),
    ('listing:read_any', 'listing', 'read_any', 'Xem mọi listing kể cả chưa ACTIVE'),
    ('listing:review_queue', 'listing', 'review_queue', 'Xem hàng đợi listing theo trạng thái'),
    ('listing:approve', 'listing', 'approve', 'Duyệt listing'),
    ('listing:reject', 'listing', 'reject', 'Từ chối listing'),
    ('listing:suspend', 'listing', 'suspend', 'Đình chỉ listing'),
    ('game_account:verify_recovery', 'game_account', 'verify_recovery', 'Xác minh quyền khôi phục tài khoản game'),
    ('game_account:flag_suspicious', 'game_account', 'flag_suspicious', 'Đánh dấu tài khoản game đáng ngờ'),
    ('user:read', 'user', 'read', 'Xem hồ sơ người dùng'),
    ('user:kyc_decide', 'user', 'kyc_decide', 'Duyệt / từ chối KYC'),
    ('user:change_status', 'user', 'change_status', 'Đổi trạng thái người dùng'),
    ('user:change_risk_tier', 'user', 'change_risk_tier', 'Đổi mức rủi ro người dùng'),
    ('user:read_abuse_signals', 'user', 'read_abuse_signals', 'Xem tín hiệu lạm dụng'),
    ('order:read_any', 'order', 'read_any', 'Xem mọi đơn thuê'),
    ('dispute:read_any', 'dispute', 'read_any', 'Xem mọi khiếu nại và hàng đợi khiếu nại'),
    ('dispute:resolve', 'dispute', 'resolve', 'Xử lý khiếu nại'),
    ('withdrawal:list_all', 'withdrawal', 'list_all', 'Xem hàng đợi rút tiền'),
    ('withdrawal:read_bank_details', 'withdrawal', 'read_bank_details', 'Xem đầy đủ thông tin ngân hàng khi rút tiền'),
    ('withdrawal:mark_paid', 'withdrawal', 'mark_paid', 'Đánh dấu đã chi'),
    ('withdrawal:mark_failed', 'withdrawal', 'mark_failed', 'Đánh dấu chi thất bại'),
    ('ledger:read', 'ledger', 'read', 'Xem sổ cái và đối soát'),
    ('approval:list', 'approval', 'list', 'Xem danh sách yêu cầu phê duyệt'),
    ('approval:request:GAME_ALLOW', 'approval', 'request:GAME_ALLOW', 'Tạo yêu cầu mở lại game'),
    ('approval:decide:GAME_ALLOW', 'approval', 'decide:GAME_ALLOW', 'Duyệt / từ chối yêu cầu mở lại game'),
    ('approval:request:ACCOUNT_SECURITY_RESTORE', 'approval', 'request:ACCOUNT_SECURITY_RESTORE', 'Tạo yêu cầu khôi phục bảo mật tài khoản game'),
    ('approval:decide:ACCOUNT_SECURITY_RESTORE', 'approval', 'decide:ACCOUNT_SECURITY_RESTORE', 'Duyệt / từ chối yêu cầu khôi phục bảo mật tài khoản game'),
    ('approval:request:SELLER_HOLD_EARLY_RELEASE', 'approval', 'request:SELLER_HOLD_EARLY_RELEASE', 'Tạo yêu cầu giải ngân sớm cho người bán'),
    ('approval:decide:SELLER_HOLD_EARLY_RELEASE', 'approval', 'decide:SELLER_HOLD_EARLY_RELEASE', 'Duyệt / từ chối yêu cầu giải ngân sớm'),
    ('approval:request:LEDGER_ADJUSTMENT', 'approval', 'request:LEDGER_ADJUSTMENT', 'Tạo yêu cầu điều chỉnh sổ cái'),
    ('approval:decide:LEDGER_ADJUSTMENT', 'approval', 'decide:LEDGER_ADJUSTMENT', 'Duyệt / từ chối yêu cầu điều chỉnh sổ cái'),
    ('role:read', 'role', 'read', 'Xem vai trò, quyền và vai trò của người dùng'),
    ('role:manage', 'role', 'manage', 'Tạo, sửa, xoá vai trò và gán quyền cho vai trò'),
    ('role:assign', 'role', 'assign', 'Gán / gỡ vai trò cho người dùng'),
    ('dev:run_jobs', 'dev', 'run_jobs', 'Chạy job thủ công (môi trường dev)');

-- Same access as the hard-coded checks before V8.
INSERT INTO role_permissions (role_code, permission_code)
SELECT 'USER', unnest(ARRAY[
    'listing:manage_own', 'game_account:manage_own', 'order:place', 'order:manage_own',
    'credential:view_own', 'dispute:open', 'wallet:withdraw']);

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'SUPPORT', unnest(ARRAY[
    'game:read_hidden', 'listing:read_any', 'listing:review_queue', 'game_account:flag_suspicious',
    'user:read', 'user:change_status', 'user:change_risk_tier', 'user:read_abuse_signals',
    'order:read_any', 'dispute:read_any', 'dispute:resolve',
    'approval:list', 'approval:request:ACCOUNT_SECURITY_RESTORE', 'approval:request:SELLER_HOLD_EARLY_RELEASE']);

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'ADMIN', unnest(ARRAY[
    'game:read_hidden', 'game:create', 'game:update', 'game:change_tos',
    'listing:read_any', 'listing:review_queue', 'listing:approve', 'listing:reject', 'listing:suspend',
    'game_account:verify_recovery', 'game_account:flag_suspicious',
    'user:read', 'user:kyc_decide', 'user:change_status', 'user:change_risk_tier', 'user:read_abuse_signals',
    'order:read_any', 'dispute:read_any', 'dispute:resolve',
    'withdrawal:list_all', 'ledger:read',
    'approval:list',
    'approval:request:GAME_ALLOW', 'approval:request:ACCOUNT_SECURITY_RESTORE', 'approval:request:SELLER_HOLD_EARLY_RELEASE',
    'approval:decide:GAME_ALLOW', 'approval:decide:ACCOUNT_SECURITY_RESTORE', 'approval:decide:SELLER_HOLD_EARLY_RELEASE',
    'approval:decide:LEDGER_ADJUSTMENT',
    'role:read', 'role:manage', 'role:assign', 'dev:run_jobs']);

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'FINANCE', unnest(ARRAY[
    'listing:read_any',
    'withdrawal:list_all', 'withdrawal:read_bank_details', 'withdrawal:mark_paid', 'withdrawal:mark_failed',
    'ledger:read', 'approval:list',
    'approval:request:SELLER_HOLD_EARLY_RELEASE', 'approval:request:LEDGER_ADJUSTMENT',
    'approval:decide:SELLER_HOLD_EARLY_RELEASE', 'approval:decide:LEDGER_ADJUSTMENT']);
