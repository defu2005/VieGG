-- Rental packages of a listing: [{"durationMinutes": 60, "price": 30000}, ...], sorted by duration.
-- An empty array means the old behaviour: rent = price_per_hour × duration.
ALTER TABLE listings ADD COLUMN pricing_tiers jsonb NOT NULL DEFAULT '[]'::jsonb;
