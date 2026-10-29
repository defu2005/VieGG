-- Per-game risk level, set by staff and shown to owners as a deposit guide.
ALTER TABLE games
    ADD COLUMN risk_level text NOT NULL DEFAULT 'MEDIUM'
        CHECK (risk_level IN ('LOW','MEDIUM','HIGH','VERY_HIGH'));
