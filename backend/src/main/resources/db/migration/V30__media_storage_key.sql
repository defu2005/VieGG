-- Media bytes can live in object storage (Cloudflare R2 / S3) instead of the database.
-- Each row keeps either the bytes (data) or the object key (storage_key), never both and never neither.
-- Existing rows keep their bytes; the backend moves them to object storage in the background when it is enabled.
ALTER TABLE listing_images ADD COLUMN storage_key text;
ALTER TABLE listing_images ALTER COLUMN data DROP NOT NULL;
ALTER TABLE listing_images ADD CONSTRAINT ck_listing_images_media CHECK ((data IS NULL) <> (storage_key IS NULL));

ALTER TABLE dispute_attachments ADD COLUMN storage_key text;
ALTER TABLE dispute_attachments ALTER COLUMN data DROP NOT NULL;
ALTER TABLE dispute_attachments ADD CONSTRAINT ck_dispute_attachments_media CHECK ((data IS NULL) <> (storage_key IS NULL));

ALTER TABLE support_attachments ADD COLUMN storage_key text;
ALTER TABLE support_attachments ALTER COLUMN data DROP NOT NULL;
ALTER TABLE support_attachments ADD CONSTRAINT ck_support_attachments_media CHECK ((data IS NULL) <> (storage_key IS NULL));

-- The background move looks for rows still holding bytes.
CREATE INDEX ix_listing_images_inline ON listing_images (id) WHERE storage_key IS NULL;
CREATE INDEX ix_dispute_attachments_inline ON dispute_attachments (id) WHERE storage_key IS NULL;
CREATE INDEX ix_support_attachments_inline ON support_attachments (id) WHERE storage_key IS NULL;
