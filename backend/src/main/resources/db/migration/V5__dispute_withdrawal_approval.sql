CREATE TABLE disputes (
    id                uuid PRIMARY KEY,
    order_id          uuid NOT NULL REFERENCES orders(id),
    opened_by         uuid NOT NULL REFERENCES users(id),
    type              text NOT NULL CHECK (type IN ('OWNER_DAMAGE','ACCOUNT_TAKEOVER','RENTER_LOGIN_FAILED',
                         'RENTER_NOT_AS_DESCRIBED')),
    status            text NOT NULL CHECK (status IN ('OPEN','RESOLVED','REJECTED')),
    description       text NOT NULL,
    evidence_urls     jsonb NOT NULL DEFAULT '[]'::jsonb,
    deadline_at       timestamptz NOT NULL,
    deposit_deduction bigint NOT NULL DEFAULT 0 CHECK (deposit_deduction >= 0),
    rent_refund       bigint NOT NULL DEFAULT 0 CHECK (rent_refund >= 0),
    decided_by        uuid REFERENCES users(id),
    decided_at        timestamptz,
    reason_code       text,
    decision_note     text,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dispute_open_per_type ON disputes (order_id, type) WHERE status = 'OPEN';
CREATE INDEX ix_disputes_status_deadline ON disputes (status, deadline_at);
CREATE INDEX ix_disputes_order ON disputes (order_id);

CREATE TABLE withdrawals (
    id               uuid PRIMARY KEY,
    owner_id         uuid NOT NULL REFERENCES users(id),
    amount           bigint NOT NULL CHECK (amount > 0),
    status           text NOT NULL CHECK (status IN ('REQUESTED','PAID','FAILED')),
    bank_snapshot    jsonb NOT NULL,
    idempotency_key  text NOT NULL,
    processed_by     uuid REFERENCES users(id),
    processed_at     timestamptz,
    bank_ref         text,
    failure_reason   text,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_withdrawals_idem ON withdrawals (owner_id, idempotency_key);
CREATE INDEX ix_withdrawals_status ON withdrawals (status, created_at);

CREATE TABLE approval_requests (
    id            uuid PRIMARY KEY,
    action_type   text NOT NULL CHECK (action_type IN ('GAME_ALLOW','ACCOUNT_SECURITY_RESTORE',
                     'SELLER_HOLD_EARLY_RELEASE','LEDGER_ADJUSTMENT')),
    payload       jsonb NOT NULL,
    requested_by  uuid NOT NULL REFERENCES users(id),
    reason_code   text NOT NULL,
    status        text NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXECUTED')),
    approved_by   uuid REFERENCES users(id),
    decided_at    timestamptz,
    executed_at   timestamptz,
    expires_at    timestamptz NOT NULL,
    result_note   text,
    idempotency_key text,
    version       bigint NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_approval_four_eyes CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE INDEX ix_approval_status ON approval_requests (status, action_type);
CREATE UNIQUE INDEX ux_approval_idem ON approval_requests (requested_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
