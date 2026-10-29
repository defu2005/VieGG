-- Photos and videos uploaded as dispute evidence. Stored in the database for Phase 0 (same as listing_images);
-- a later phase can move the bytes to object storage and keep this table as the index.
-- Evidence is append-only: there is no delete path.
CREATE TABLE dispute_attachments (
    id            uuid PRIMARY KEY,
    dispute_id    uuid NOT NULL REFERENCES disputes(id),
    uploaded_by   uuid NOT NULL REFERENCES users(id),
    content_type  text NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp',
                     'video/mp4','video/quicktime','video/webm')),
    size_bytes    int NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    data          bytea NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_dispute_attachments_dispute ON dispute_attachments (dispute_id, created_at);
