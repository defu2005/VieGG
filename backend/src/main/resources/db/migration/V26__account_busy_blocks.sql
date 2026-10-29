-- Owners mark times when their account cannot be rented (they play it themselves, travel...).
-- Renters see these as busy on the calendar, search skips them and no order may overlap them.

CREATE TABLE account_blocks (
    id              uuid PRIMARY KEY,
    game_account_id uuid NOT NULL REFERENCES game_accounts(id),
    owner_id        uuid NOT NULL REFERENCES users(id),
    start_at        timestamptz NOT NULL,
    end_at          timestamptz NOT NULL,
    note            text CHECK (note IS NULL OR length(note) <= 200),
    period          tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_blocks_range CHECK (end_at > start_at),
    CONSTRAINT ex_account_blocks_no_overlap EXCLUDE USING gist (
        game_account_id WITH =,
        period          WITH &&
    )
);
CREATE INDEX ix_account_blocks_account_end ON account_blocks (game_account_id, end_at);

-- One lock per account calendar: taken by an order that grows its slot and by an owner adding busy time,
-- so the two checks below cannot both pass at the same moment.
CREATE FUNCTION account_calendar_lock(account uuid) RETURNS void LANGUAGE sql AS $$
    SELECT pg_advisory_xact_lock(hashtextextended('account-calendar:' || account::text, 0));
$$;

-- Orders cannot overlap the owner's busy time. Raised as an exclusion violation on ex_orders_no_overlap so every
-- existing "slot taken" handler applies; the message also names account_busy_by_owner for a clearer error.
CREATE FUNCTION orders_check_account_blocks() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL') THEN
        RETURN NULL;
    END IF;
    -- Already reserved and only shrinking (or unchanged): nothing new to check.
    IF TG_OP = 'UPDATE'
       AND OLD.status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL')
       AND NEW.rental_period <@ OLD.rental_period THEN
        RETURN NULL;
    END IF;
    PERFORM account_calendar_lock(NEW.game_account_id);
    IF EXISTS (SELECT 1 FROM account_blocks b
                WHERE b.game_account_id = NEW.game_account_id AND b.period && NEW.rental_period) THEN
        RAISE EXCEPTION 'conflicting key value violates exclusion constraint "ex_orders_no_overlap" (account_busy_by_owner)'
            USING ERRCODE = 'exclusion_violation', CONSTRAINT = 'ex_orders_no_overlap', TABLE = 'orders';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_orders_account_blocks AFTER INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_check_account_blocks();
