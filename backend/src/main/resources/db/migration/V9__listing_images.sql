-- Screenshots of the rented account (skins, rank...) shown on a listing. Stored in the database for Phase 0;
-- a later phase can move the bytes to object storage and keep this table as the index.
CREATE TABLE listing_images (
    id            uuid PRIMARY KEY,
    listing_id    uuid NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    position      int NOT NULL,
    content_type  text NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    size_bytes    int NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 1048576),
    data          bytea NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_listing_images_listing ON listing_images (listing_id, position);
