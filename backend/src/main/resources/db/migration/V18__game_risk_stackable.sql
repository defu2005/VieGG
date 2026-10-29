-- OWNER risks are either stackable (rank drop, reputation drop… can happen together: deductions add up) or
-- exclusive (permanent ban by the publisher…: when it happens the renter pays only that risk's deposit).
-- Order deposit = max(sum of stackable deposits, largest exclusive deposit).
ALTER TABLE game_risks ADD COLUMN stackable boolean NOT NULL DEFAULT true;
