-- A renter complaint (RENTER_RISK) can list several renter risks of the order. Support decides each risk on its
-- own (ACCEPTED / REJECTED); when the last one is decided the money is computed and paid automatically:
-- rent refund (once, the rent still held) + the owner's compensation of every accepted risk.
CREATE TABLE dispute_items (
    id            uuid PRIMARY KEY,
    dispute_id    uuid NOT NULL REFERENCES disputes(id),
    order_id      uuid NOT NULL REFERENCES orders(id),
    risk_id       uuid NOT NULL REFERENCES game_risks(id),
    position      int NOT NULL DEFAULT 0,
    status        text NOT NULL CHECK (status IN ('OPEN','ACCEPTED','REJECTED')),
    -- ACCEPTED: the owner's compensation for this risk (from the order snapshot), 0 = refund only.
    compensation  bigint NOT NULL DEFAULT 0 CHECK (compensation >= 0),
    reason_code   text,
    note          text,
    decided_by    uuid REFERENCES users(id),
    decided_at    timestamptz,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dispute_id, risk_id)
);
CREATE INDEX ix_dispute_items_dispute ON dispute_items (dispute_id, position);
-- A risk can be in only one open complaint of the order at a time.
CREATE UNIQUE INDEX ux_dispute_item_open_risk ON dispute_items (order_id, risk_id) WHERE status = 'OPEN';

-- Renter risk complaints are unique per risk through their items, not per (order, type, risk_id).
DROP INDEX ux_dispute_open_per_type;
CREATE UNIQUE INDEX ux_dispute_open_per_type ON disputes
    (order_id, type, coalesce(risk_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'OPEN' AND type <> 'RENTER_RISK';

-- Open single-risk complaints made before this change get their one item, so they are decided the same way.
INSERT INTO dispute_items (id, dispute_id, order_id, risk_id, position, status, created_at, updated_at)
SELECT gen_random_uuid(), d.id, d.order_id, d.risk_id, 0, 'OPEN', d.created_at, d.created_at
  FROM disputes d
 WHERE d.type = 'RENTER_RISK' AND d.status = 'OPEN' AND d.risk_id IS NOT NULL;
