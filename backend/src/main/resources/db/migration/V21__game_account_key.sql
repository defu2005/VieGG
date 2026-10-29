-- Per-game rule for what makes a game account unique (UID, UUID, in-game name...). LOGIN keeps the V11 behaviour:
-- the login username fingerprint is the only key.
ALTER TABLE games
    ADD COLUMN account_key_type text NOT NULL DEFAULT 'LOGIN'
        CHECK (account_key_type IN ('LOGIN','UUID','NUMERIC_ID','NAME')),
    ADD COLUMN account_key_label text,
    ADD COLUMN account_key_example text,
    ADD COLUMN account_key_per_server boolean NOT NULL DEFAULT false;

-- account_key: normalized identifier (public in-game id, stored in clear so staff can search it).
-- account_key_scope: normalized server region when the game's ids are unique per server, '' otherwise.
-- One identifier = one game account on the whole platform, whoever owns it (also blocks a banned owner re-listing
-- the same account from a new RentGG user).
ALTER TABLE game_accounts
    ADD COLUMN account_key text,
    ADD COLUMN account_key_display text,
    ADD COLUMN account_key_scope text NOT NULL DEFAULT '';
CREATE UNIQUE INDEX ux_game_accounts_key ON game_accounts (game_id, account_key_scope, account_key)
    WHERE account_key IS NOT NULL;
