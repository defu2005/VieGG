-- Risks of renting out a game's accounts, defined by staff per game.
--   side OWNER : damage the owner suffers (renter breaks the account)  -> owner sets a deposit the renter pays.
--   side RENTER: damage the renter suffers (wrong password, not as described) -> owner may set a compensation;
--                without one the renter only gets the rent back.
-- complaint_window_minutes (optional): how long a complaint about this risk can be opened, counted from the
-- renter's first credential view (RENTER) or from the end of the rental (OWNER).
-- Risks are archived, never deleted: listings, order snapshots and disputes keep pointing at them.
CREATE TABLE game_risks (
    id                        uuid PRIMARY KEY,
    game_id                   uuid NOT NULL REFERENCES games(id),
    side                      text NOT NULL CHECK (side IN ('OWNER','RENTER')),
    name                      text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    description               text,
    recommended_amount        bigint NOT NULL DEFAULT 0 CHECK (recommended_amount >= 0),
    complaint_window_minutes  int CHECK (complaint_window_minutes IS NULL OR complaint_window_minutes BETWEEN 1 AND 43200),
    position                  int NOT NULL DEFAULT 0,
    archived_at               timestamptz,
    version                   bigint NOT NULL DEFAULT 0,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_game_risks_game ON game_risks (game_id, position);

-- Owner's amount per risk: [{"riskId": "...", "amount": 100000}, ...].
-- OWNER risks: deposit (listing.deposit_amount = their sum). RENTER risks: compensation, absent = refund only.
ALTER TABLE listings ADD COLUMN risk_terms jsonb NOT NULL DEFAULT '[]'::jsonb;

-- Disputes about one risk of the order's snapshot.
ALTER TABLE disputes ADD COLUMN risk_id uuid REFERENCES game_risks(id);
ALTER TABLE disputes ADD COLUMN compensation bigint NOT NULL DEFAULT 0 CHECK (compensation >= 0);
ALTER TABLE disputes DROP CONSTRAINT disputes_type_check;
ALTER TABLE disputes ADD CONSTRAINT disputes_type_check CHECK (type IN ('OWNER_DAMAGE','ACCOUNT_TAKEOVER',
    'RENTER_LOGIN_FAILED','RENTER_NOT_AS_DESCRIBED','OWNER_RISK','RENTER_RISK'));
DROP INDEX ux_dispute_open_per_type;
CREATE UNIQUE INDEX ux_dispute_open_per_type ON disputes
    (order_id, type, coalesce(risk_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 'OPEN';

-- Owner -> renter compensation for a RENTER risk.
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check CHECK (type IN (
    'PAYMENT_CAPTURED','ORDER_ENDED','SELLER_HOLD_RELEASED','SELLER_DISPUTE_HOLD','SELLER_DISPUTE_HOLD_RELEASED',
    'DEPOSIT_REFUND_REQUESTED','REFUND_COMPLETED','DEPOSIT_DEDUCTED','RENT_REFUND','ORDER_CANCELLED_REFUND',
    'ORDER_CANCELLED_REVENUE','WITHDRAWAL_REQUESTED','WITHDRAWAL_PAID','WITHDRAWAL_FAILED','LEDGER_ADJUSTMENT',
    'WALLET_TOPUP','WALLET_PAYMENT','RISK_COMPENSATION'));
