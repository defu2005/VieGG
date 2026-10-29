CREATE TABLE games (
    id                              uuid PRIMARY KEY,
    code                            text NOT NULL,
    name                            text NOT NULL,
    publisher                       text,
    tos_status                      text NOT NULL DEFAULT 'REVIEW_REQUIRED'
                                        CHECK (tos_status IN ('ALLOWED','REVIEW_REQUIRED','PROHIBITED','SUSPENDED')),
    tos_url                         text,
    tos_reviewed_at                 timestamptz,
    handover_mode                   text NOT NULL DEFAULT 'STATIC_CREDENTIAL'
                                        CHECK (handover_mode IN ('STATIC_CREDENTIAL','OWNER_ASSISTED_OTP')),
    password_change_kicks_sessions  boolean NOT NULL DEFAULT false,
    deposit_min                     bigint NOT NULL,
    deposit_max                     bigint NOT NULL,
    deposit_hold_hours              int NOT NULL DEFAULT 72,
    seller_hold_hours               int NOT NULL DEFAULT 72,
    seller_hold_hours_high_risk     int NOT NULL DEFAULT 168,
    complaint_window_minutes        int NOT NULL DEFAULT 15,
    min_duration_minutes            int NOT NULL DEFAULT 60,
    max_duration_minutes            int NOT NULL DEFAULT 1440,
    grace_minutes                   int NOT NULL DEFAULT 10,
    platform_fee_bps                int NOT NULL DEFAULT 1000,
    renter_free_cancel_minutes      int NOT NULL DEFAULT 120,
    late_cancel_refund_bps          int NOT NULL DEFAULT 5000,
    owner_free_cancel_hours         int NOT NULL DEFAULT 12,
    min_age                         int NOT NULL DEFAULT 18,
    risk_notes                      text,
    version                         bigint NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_games_code UNIQUE (code),
    CONSTRAINT ck_games_deposit CHECK (deposit_min > 0 AND deposit_max >= deposit_min),
    CONSTRAINT ck_games_deposit_hold CHECK (deposit_hold_hours BETWEEN 1 AND 720),
    CONSTRAINT ck_games_seller_hold CHECK (seller_hold_hours BETWEEN 0 AND 720
                                           AND seller_hold_hours_high_risk >= seller_hold_hours),
    CONSTRAINT ck_games_complaint CHECK (complaint_window_minutes BETWEEN 5 AND 120),
    CONSTRAINT ck_games_duration CHECK (min_duration_minutes > 0 AND max_duration_minutes >= min_duration_minutes),
    CONSTRAINT ck_games_grace CHECK (grace_minutes BETWEEN 0 AND 30),
    CONSTRAINT ck_games_fee CHECK (platform_fee_bps BETWEEN 0 AND 5000),
    CONSTRAINT ck_games_cancel CHECK (renter_free_cancel_minutes >= 0
                                      AND late_cancel_refund_bps BETWEEN 0 AND 10000
                                      AND owner_free_cancel_hours >= 0),
    CONSTRAINT ck_games_min_age CHECK (min_age >= 18)
);

CREATE TABLE game_accounts (
    id                          uuid PRIMARY KEY,
    owner_id                    uuid NOT NULL REFERENCES users(id),
    game_id                     uuid NOT NULL REFERENCES games(id),
    server_region               text,
    login_hint                  text NOT NULL,
    security_state              text NOT NULL CHECK (security_state IN ('PENDING_VERIFICATION','AVAILABLE','IN_USE',
                                    'AWAITING_RECLAIM','ROTATION_PENDING','ROTATION_OVERDUE','SUSPENDED_SECURITY_REVIEW')),
    security_state_changed_at   timestamptz NOT NULL DEFAULT now(),
    rotation_due_at             timestamptz,
    recovery_email_owned        boolean NOT NULL,
    recovery_phone_owned        boolean NOT NULL,
    two_factor_owned            boolean NOT NULL,
    attested_at                 timestamptz NOT NULL,
    recovery_verified_by        uuid REFERENCES users(id),
    recovery_verified_at        timestamptz,
    suspended_at                timestamptz,
    version                     bigint NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_game_accounts_attestation CHECK (recovery_email_owned AND recovery_phone_owned AND two_factor_owned)
);
CREATE INDEX ix_game_accounts_owner ON game_accounts (owner_id);
CREATE INDEX ix_game_accounts_state_due ON game_accounts (security_state, rotation_due_at);

CREATE TABLE credential_versions (
    id              uuid PRIMARY KEY,
    game_account_id uuid NOT NULL REFERENCES game_accounts(id),
    version         int NOT NULL,
    ciphertext      bytea NOT NULL,
    iv              bytea NOT NULL,
    key_id          text NOT NULL,
    status          text NOT NULL CHECK (status IN ('ACTIVE','RETIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    retired_at      timestamptz,
    CONSTRAINT ux_credential_versions UNIQUE (game_account_id, version)
);
CREATE UNIQUE INDEX ux_credential_active ON credential_versions (game_account_id) WHERE status = 'ACTIVE';

CREATE TABLE listings (
    id              uuid PRIMARY KEY,
    game_account_id uuid NOT NULL REFERENCES game_accounts(id),
    owner_id        uuid NOT NULL REFERENCES users(id),
    game_id         uuid NOT NULL REFERENCES games(id),
    title           text NOT NULL,
    description     text,
    attributes      jsonb NOT NULL DEFAULT '{}'::jsonb,
    price_per_hour  bigint NOT NULL CHECK (price_per_hour > 0),
    deposit_amount  bigint NOT NULL CHECK (deposit_amount > 0),
    auto_accept     boolean NOT NULL DEFAULT false,
    end_mode        text NOT NULL CHECK (end_mode IN ('FORCE_AT_END','GRACE_FOR_CURRENT_MATCH','STAY_ONLINE_UNTIL_RECLAIM')),
    status          text NOT NULL CHECK (status IN ('DRAFT','PENDING_REVIEW','ACTIVE','PAUSED','REJECTED','SUSPENDED')),
    review_note     text,
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_listings_one_active_per_account ON listings (game_account_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_listings_status_game ON listings (status, game_id, price_per_hour);
CREATE INDEX ix_listings_owner ON listings (owner_id);
