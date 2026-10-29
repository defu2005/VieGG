-- Keyed HMAC of (game, normalized username): lets us refuse the same game login being added twice without
-- storing the username in clear. Existing rows are filled at startup by LoginFingerprintBackfill.
ALTER TABLE game_accounts ADD COLUMN login_fingerprint text;
CREATE UNIQUE INDEX ux_game_accounts_login ON game_accounts (game_id, login_fingerprint)
    WHERE login_fingerprint IS NOT NULL;
