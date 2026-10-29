CREATE TABLE ledger_accounts (
    id            uuid PRIMARY KEY,
    code          text NOT NULL CHECK (code IN ('PSP_CLEARING','RENT_ESCROW','DEPOSIT_HELD','REFUND_PAYABLE',
                     'PLATFORM_REVENUE','SELLER_REVIEW_HOLD','SELLER_DISPUTE_HOLD','SELLER_AVAILABLE',
                     'SELLER_PAYOUT_PROCESSING')),
    owner_user_id uuid REFERENCES users(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_ledger_accounts_code_owner UNIQUE NULLS NOT DISTINCT (code, owner_user_id),
    CONSTRAINT ck_ledger_accounts_scope CHECK (
        (code LIKE 'SELLER\_%' AND owner_user_id IS NOT NULL) OR
        (code NOT LIKE 'SELLER\_%' AND owner_user_id IS NULL))
);

INSERT INTO ledger_accounts (id, code, owner_user_id) VALUES
    ('00000000-0000-0000-0000-000000000001', 'PSP_CLEARING', NULL),
    ('00000000-0000-0000-0000-000000000002', 'RENT_ESCROW', NULL),
    ('00000000-0000-0000-0000-000000000003', 'DEPOSIT_HELD', NULL),
    ('00000000-0000-0000-0000-000000000004', 'REFUND_PAYABLE', NULL),
    ('00000000-0000-0000-0000-000000000005', 'PLATFORM_REVENUE', NULL);

CREATE TABLE ledger_transactions (
    id               uuid PRIMARY KEY,
    type             text NOT NULL CHECK (type IN ('PAYMENT_CAPTURED','ORDER_ENDED','SELLER_HOLD_RELEASED',
                        'SELLER_DISPUTE_HOLD','SELLER_DISPUTE_HOLD_RELEASED','DEPOSIT_REFUND_REQUESTED',
                        'REFUND_COMPLETED','DEPOSIT_DEDUCTED','RENT_REFUND','ORDER_CANCELLED_REFUND',
                        'ORDER_CANCELLED_REVENUE','WITHDRAWAL_REQUESTED','WITHDRAWAL_PAID','WITHDRAWAL_FAILED',
                        'LEDGER_ADJUSTMENT')),
    order_id         uuid REFERENCES orders(id),
    reference        text,
    idempotency_key  text NOT NULL,
    created_by       uuid,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_ledger_tx_idem ON ledger_transactions (idempotency_key);
CREATE INDEX ix_ledger_tx_order ON ledger_transactions (order_id);

CREATE TABLE ledger_postings (
    id              uuid PRIMARY KEY,
    transaction_id  uuid NOT NULL REFERENCES ledger_transactions(id),
    account_id      uuid NOT NULL REFERENCES ledger_accounts(id),
    order_id        uuid REFERENCES orders(id),
    amount          bigint NOT NULL CHECK (amount <> 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ledger_postings_account ON ledger_postings (account_id);
CREATE INDEX ix_ledger_postings_account_order ON ledger_postings (account_id, order_id);
CREATE INDEX ix_ledger_postings_tx ON ledger_postings (transaction_id);
