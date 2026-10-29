-- Owners can add their own risks to a listing, on top of the game's risks defined by staff. They live in
-- game_risks (so order snapshots, disputes and dispute items keep their foreign key) with listing_id set;
-- game-level risk lists only use rows where listing_id IS NULL. Archived, never deleted, like game risks.
ALTER TABLE game_risks ADD COLUMN listing_id uuid REFERENCES listings(id);
CREATE INDEX ix_game_risks_listing ON game_risks (listing_id, position) WHERE listing_id IS NOT NULL;
