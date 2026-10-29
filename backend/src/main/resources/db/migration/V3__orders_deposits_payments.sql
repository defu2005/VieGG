CREATE TABLE orders (
    id                          uuid PRIMARY KEY,
    listing_id                  uuid NOT NULL REFERENCES listings(id),
    game_account_id             uuid NOT NULL REFERENCES game_accounts(id),
    game_id                     uuid NOT NULL REFERENCES games(id),
    renter_id                   uuid NOT NULL REFERENCES users(id),
    owner_id                    uuid NOT NULL REFERENCES users(id),
    status                      text NOT NULL CHECK (status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL',
                                    'CONFIRMED','IN_RENTAL','ENDED','CANCELLED','EXPIRED','REJECTED')),
    start_at                    timestamptz NOT NULL,
    scheduled_end_at            timestamptz NOT NULL,
    block_end_at                timestamptz NOT NULL,
    actual_end_at               timestamptz,
    rent_amount                 bigint NOT NULL CHECK (rent_amount > 0),
    platform_fee                bigint NOT NULL CHECK (platform_fee >= 0),
    seller_net                  bigint NOT NULL,
    deposit_amount              bigint NOT NULL CHECK (deposit_amount > 0),
    policy_snapshot             jsonb NOT NULL,
    payment_expires_at          timestamptz NOT NULL,
    approval_expires_at         timestamptz,
    credential_first_viewed_at  timestamptz,
    handover_confirmed_at       timestamptz,
    otp_note                    text,
    otp_note_expires_at         timestamptz,
    cancel_reason               text CHECK (cancel_reason IN ('RENTER_CANCELLED','RENTER_LATE_CANCELLED','OWNER_CANCELLED',
                                    'OWNER_FAULT_NOT_READY','PLATFORM_GAME_SUSPENDED','LATE_PAYMENT',
                                    'OWNER_REJECTED','APPROVAL_TIMEOUT')),
    cancelled_by                uuid,
    seller_hold_until           timestamptz,
    seller_released_at          timestamptz,
    ending_reminder_sent_at     timestamptz,
    idempotency_key             text NOT NULL,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    rental_period               tstzrange GENERATED ALWAYS AS (tstzrange(start_at, block_end_at, '[)')) STORED,
    CONSTRAINT ck_orders_time CHECK (start_at < scheduled_end_at AND scheduled_end_at <= block_end_at),
    CONSTRAINT ck_orders_money CHECK (seller_net = rent_amount - platform_fee),
    CONSTRAINT ux_orders_idem UNIQUE (renter_id, idempotency_key),
    CONSTRAINT ex_orders_no_overlap EXCLUDE USING gist (
        game_account_id WITH =,
        rental_period   WITH &&
    ) WHERE (status IN ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL'))
);
CREATE INDEX ix_orders_status_start ON orders (status, start_at);
CREATE INDEX ix_orders_status_block_end ON orders (status, block_end_at);
CREATE INDEX ix_orders_status_payment_exp ON orders (status, payment_expires_at);
CREATE INDEX ix_orders_status_approval_exp ON orders (status, approval_expires_at);
CREATE INDEX ix_orders_seller_hold ON orders (seller_hold_until) WHERE seller_released_at IS NULL;
CREATE INDEX ix_orders_renter ON orders (renter_id, created_at DESC);
CREATE INDEX ix_orders_owner ON orders (owner_id, created_at DESC);

CREATE TABLE deposits (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL UNIQUE REFERENCES orders(id),
    renter_id        uuid NOT NULL REFERENCES users(id),
    amount           bigint NOT NULL CHECK (amount > 0),
    status           text NOT NULL CHECK (status IN ('PENDING_PAYMENT','HELD','POST_RENTAL_HOLD',
                       'DISPUTED','REFUND_PENDING','REFUNDED','SETTLED','VOID')),
    hold_until       timestamptz,
    deducted_amount  bigint NOT NULL DEFAULT 0 CHECK (deducted_amount >= 0),
    refunded_amount  bigint NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_deposit_sum CHECK (deducted_amount + refunded_amount <= amount),
    CONSTRAINT ck_deposit_terminal CHECK (status NOT IN ('REFUNDED','SETTLED')
                                          OR deducted_amount + refunded_amount = amount)
);
CREATE INDEX ix_deposits_status_hold ON deposits (status, hold_until);
CREATE INDEX ix_deposits_renter ON deposits (renter_id, created_at DESC);

CREATE TABLE payment_intents (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL REFERENCES orders(id),
    payer_id         uuid NOT NULL REFERENCES users(id),
    provider         text NOT NULL,
    provider_ref     text,
    amount           bigint NOT NULL CHECK (amount > 0),
    status           text NOT NULL CHECK (status IN ('CREATED','SUCCEEDED','FAILED','EXPIRED')),
    checkout_url     text,
    idempotency_key  text NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_payment_intents_idem UNIQUE (order_id, idempotency_key)
);
CREATE UNIQUE INDEX ux_payment_provider_ref ON payment_intents (provider, provider_ref) WHERE provider_ref IS NOT NULL;
CREATE INDEX ix_payment_intents_order ON payment_intents (order_id);

CREATE TABLE refunds (
    id                uuid PRIMARY KEY,
    order_id          uuid NOT NULL REFERENCES orders(id),
    payment_intent_id uuid NOT NULL REFERENCES payment_intents(id),
    kind              text NOT NULL CHECK (kind IN ('RENT','DEPOSIT')),
    amount            bigint NOT NULL CHECK (amount > 0),
    status            text NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    provider_ref      text,
    reason            text NOT NULL,
    attempts          int NOT NULL DEFAULT 0,
    last_error        text,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refunds_order ON refunds (order_id);
CREATE INDEX ix_refunds_status ON refunds (status);
