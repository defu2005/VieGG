-- UC-17 (P1): renter extends a running rental. The extra time is reserved while the renter pays
-- (orders.extension_hold_until) and only becomes part of the rental once paid.

ALTER TABLE orders ADD COLUMN extension_hold_until timestamptz;
ALTER TABLE orders ADD COLUMN extended_minutes int NOT NULL DEFAULT 0 CHECK (extended_minutes >= 0);
ALTER TABLE orders ADD COLUMN extension_rent_amount bigint NOT NULL DEFAULT 0 CHECK (extension_rent_amount >= 0);

-- The slot now also covers a pending extension, so nobody can book the time the renter is paying for.
ALTER TABLE orders DROP CONSTRAINT ex_orders_no_overlap;
ALTER TABLE orders DROP COLUMN rental_period;
ALTER TABLE orders ADD COLUMN rental_period tstzrange GENERATED ALWAYS AS (
    tstzrange(start_at, GREATEST(block_end_at, COALESCE(extension_hold_until, block_end_at)), '[)')) STORED;
ALTER TABLE orders ADD CONSTRAINT ex_orders_no_overlap EXCLUDE USING gist (
    game_account_id WITH =,
    rental_period   WITH &&
) WHERE (status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL'));

CREATE TABLE order_extensions (
    id                    uuid PRIMARY KEY,
    order_id              uuid NOT NULL REFERENCES orders(id),
    renter_id             uuid NOT NULL REFERENCES users(id),
    duration_minutes      int NOT NULL CHECK (duration_minutes > 0),
    rent_amount           bigint NOT NULL CHECK (rent_amount > 0),
    platform_fee          bigint NOT NULL CHECK (platform_fee >= 0),
    status                text NOT NULL CHECK (status IN ('PENDING_PAYMENT','PAID','EXPIRED','CANCELLED')),
    new_scheduled_end_at  timestamptz NOT NULL,
    new_block_end_at      timestamptz NOT NULL,
    payment_expires_at    timestamptz NOT NULL,
    payment_intent_id     uuid REFERENCES payment_intents(id),
    paid_at               timestamptz,
    idempotency_key       text NOT NULL,
    version               bigint NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_order_extensions_idem UNIQUE (order_id, idempotency_key)
);
-- At most one extension waiting for payment per order.
CREATE UNIQUE INDEX ux_order_extensions_one_pending ON order_extensions (order_id) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_order_extensions_order ON order_extensions (order_id, created_at);
CREATE INDEX ix_order_extensions_pending_exp ON order_extensions (payment_expires_at) WHERE status = 'PENDING_PAYMENT';
