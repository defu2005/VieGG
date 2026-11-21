-- Kháng nghị quyết định kiểm duyệt listing (Dev Guide §3 mục 12, UC-05).
-- Mỗi lần từ chối hoặc đình chỉ được ghi lại để chủ listing kháng nghị đúng quyết định đó.
CREATE TABLE listing_moderation_decisions (
    id          uuid PRIMARY KEY,
    listing_id  uuid NOT NULL REFERENCES listings(id),
    kind        text NOT NULL CHECK (kind IN ('REJECTED', 'SUSPENDED')),
    note        text NOT NULL,
    decided_by  uuid NOT NULL REFERENCES users(id),
    decided_at  timestamptz NOT NULL
);
CREATE INDEX ix_listing_decisions_listing ON listing_moderation_decisions (listing_id, decided_at);

CREATE TABLE listing_appeals (
    id               uuid PRIMARY KEY,
    decision_id      uuid NOT NULL REFERENCES listing_moderation_decisions(id),
    listing_id       uuid NOT NULL REFERENCES listings(id),
    appellant_id     uuid NOT NULL REFERENCES users(id),
    reason           text NOT NULL,
    status           text NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DISMISSED')),
    created_at       timestamptz NOT NULL,
    resolved_by      uuid REFERENCES users(id),
    resolved_at      timestamptz,
    resolution_note  text,
    CONSTRAINT uq_listing_appeal_once UNIQUE (decision_id, appellant_id),
    CONSTRAINT ck_listing_appeal_resolved CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);
CREATE INDEX ix_listing_appeals_status ON listing_appeals (status, created_at);

-- Quyền xét kháng nghị: chỉ ADMIN.
INSERT INTO permissions (code, resource, action, description) VALUES
    ('listing:appeal_review', 'listing', 'appeal_review', 'Xét kháng nghị kiểm duyệt listing')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_code, permission_code)
SELECT r, 'listing:appeal_review' FROM unnest(ARRAY['ADMIN']) AS r
WHERE EXISTS (SELECT 1 FROM roles WHERE code = r)
ON CONFLICT DO NOTHING;

-- Xét kháng nghị khiếu nại (UC-13): support ghi kết quả chấp nhận hoặc bác bỏ. Kết quả không đảo tiền của quyết định.
ALTER TABLE dispute_appeals DROP CONSTRAINT IF EXISTS dispute_appeals_status_check;
ALTER TABLE dispute_appeals
    ADD COLUMN resolved_by      uuid REFERENCES users(id),
    ADD COLUMN resolved_at      timestamptz,
    ADD COLUMN resolution_note  text,
    ADD CONSTRAINT dispute_appeals_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'DISMISSED')),
    ADD CONSTRAINT dispute_appeals_resolved_check CHECK ((status = 'PENDING') = (resolved_at IS NULL));
