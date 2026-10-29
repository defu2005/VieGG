-- Owner-chosen free-cancel window for renters (minutes before the start). NULL = follow the game's default.
ALTER TABLE listings ADD COLUMN renter_free_cancel_minutes integer
    CHECK (renter_free_cancel_minutes IS NULL OR renter_free_cancel_minutes BETWEEN 0 AND 10080);
