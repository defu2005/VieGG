-- Kháng nghị quyết định khiếu nại (Dev Guide §3 mục 12): chủ account được kháng nghị một lần cho mỗi khiếu nại.
CREATE TABLE dispute_appeals (
    id            uuid PRIMARY KEY,
    dispute_id    uuid NOT NULL REFERENCES disputes(id),
    appellant_id  uuid NOT NULL REFERENCES users(id),
    reason        text NOT NULL,
    status        text NOT NULL CHECK (status IN ('PENDING')),
    created_at    timestamptz NOT NULL,
    CONSTRAINT uq_dispute_appeal_once UNIQUE (dispute_id, appellant_id)
);
CREATE INDEX ix_dispute_appeals_dispute ON dispute_appeals (dispute_id, created_at);
