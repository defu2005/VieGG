CREATE TABLE audit_logs (
    id           uuid PRIMARY KEY,
    actor_id     uuid,
    actor_roles  text,
    action       text NOT NULL,
    entity_type  text NOT NULL,
    entity_id    uuid,
    before       jsonb,
    after        jsonb,
    reason_code  text,
    ip           text,
    user_agent   text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id, created_at);
CREATE INDEX ix_audit_action ON audit_logs (action, created_at);

CREATE TABLE notifications (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id),
    event_id    uuid,
    type        text NOT NULL,
    title       text NOT NULL,
    body        text NOT NULL,
    data        jsonb,
    read_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_notifications_event_user ON notifications (event_id, user_id) WHERE event_id IS NOT NULL;
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    aggregate_type  text NOT NULL,
    aggregate_id    uuid,
    event_type      text NOT NULL,
    payload         jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);
