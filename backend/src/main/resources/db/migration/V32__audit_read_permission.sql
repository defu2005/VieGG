-- Admin audit lookup (Dev Guide §3 mục 11): a read-only permission for ADMIN.
INSERT INTO permissions (code, resource, action, description) VALUES
    ('audit:read', 'audit', 'read', 'Tra cứu nhật ký audit')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_code, permission_code)
SELECT r, 'audit:read' FROM unnest(ARRAY['ADMIN']) AS r
WHERE EXISTS (SELECT 1 FROM roles WHERE code = r)
ON CONFLICT DO NOTHING;
