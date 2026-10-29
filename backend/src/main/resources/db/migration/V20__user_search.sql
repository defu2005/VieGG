-- Staff user search: lower-case and strip Vietnamese accents so "nguyen van a" finds "Nguyễn Văn A".
CREATE OR REPLACE FUNCTION rentgg_fold(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT translate(lower(coalesce(t, '')),
                 'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ',
                 repeat('a', 17) || repeat('e', 11) || repeat('i', 5) || repeat('o', 17) || repeat('u', 11)
                     || repeat('y', 5) || 'd')
$$;

CREATE INDEX IF NOT EXISTS ix_users_created_at ON users (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_users_kyc_status ON users (kyc_status) WHERE kyc_status = 'PENDING';
