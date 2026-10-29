CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id              uuid PRIMARY KEY,
    email           text NOT NULL,
    password_hash   text NOT NULL,
    display_name    text NOT NULL,
    date_of_birth   date NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESTRICTED','SUSPENDED')),
    kyc_status      text NOT NULL DEFAULT 'NONE' CHECK (kyc_status IN ('NONE','PENDING','VERIFIED','REJECTED')),
    risk_tier       text NOT NULL DEFAULT 'STANDARD' CHECK (risk_tier IN ('STANDARD','HIGH')),
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email))
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users(id),
    role    text NOT NULL CHECK (role IN ('USER','SUPPORT','ADMIN','FINANCE')),
    PRIMARY KEY (user_id, role)
);
CREATE INDEX ix_user_roles_role ON user_roles (role);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id),
    token_hash  text NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE kyc_submissions (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id),
    payload     jsonb NOT NULL,
    status      text NOT NULL CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
    reviewed_by uuid REFERENCES users(id),
    reviewed_at timestamptz,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_kyc_user ON kyc_submissions (user_id, created_at DESC);

CREATE TABLE abuse_signals (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id),
    type        text NOT NULL,
    order_id    uuid,
    details     jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_abuse_signals_user ON abuse_signals (user_id, created_at DESC);
