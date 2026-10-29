-- Rental packages staff suggest for a game: [{"durationMinutes": 60, "price": 30000}, ...] sorted by duration.
-- "price" may be null (staff only suggest the duration). Owners pick which of them each listing offers and may
-- still add their own; listings keep their own copy in listings.pricing_tiers.
ALTER TABLE games ADD COLUMN suggested_packages jsonb NOT NULL DEFAULT '[]'::jsonb;
