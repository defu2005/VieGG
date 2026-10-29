-- Help desk: users chat with staff (questions, support, complaints); staff can invite other users
-- (e.g. both sides of an order) into the conversation to mediate a dispute.

CREATE TABLE support_tickets (
    id                   uuid PRIMARY KEY,
    subject              text NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
    category             text NOT NULL CHECK (category IN ('QUESTION','SUPPORT','COMPLAINT','MEDIATION')),
    status               text NOT NULL CHECK (status IN ('OPEN','WAITING_USER','RESOLVED','CLOSED')),
    created_by           uuid NOT NULL REFERENCES users(id),
    order_id             uuid REFERENCES orders(id),
    dispute_id           uuid REFERENCES disputes(id),
    assignee_id          uuid REFERENCES users(id),
    last_message_at      timestamptz NOT NULL,
    last_message_preview text,
    closed_at            timestamptz,
    version              bigint NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_tickets_status ON support_tickets (status, last_message_at DESC);
CREATE INDEX ix_support_tickets_assignee ON support_tickets (assignee_id, status);
CREATE INDEX ix_support_tickets_order ON support_tickets (order_id);

-- Who is in the conversation. Users invited later (role PARTY) only see messages from joined_at on.
CREATE TABLE support_ticket_participants (
    id           uuid PRIMARY KEY,
    ticket_id    uuid NOT NULL REFERENCES support_tickets(id),
    user_id      uuid NOT NULL REFERENCES users(id),
    role         text NOT NULL CHECK (role IN ('REQUESTER','PARTY','STAFF')),
    added_by     uuid REFERENCES users(id),
    joined_at    timestamptz NOT NULL,
    left_at      timestamptz,
    last_read_at timestamptz,
    UNIQUE (ticket_id, user_id)
);
CREATE INDEX ix_support_participants_user ON support_ticket_participants (user_id) WHERE left_at IS NULL;

CREATE TABLE support_attachments (
    id           uuid PRIMARY KEY,
    ticket_id    uuid NOT NULL REFERENCES support_tickets(id),
    uploaded_by  uuid NOT NULL REFERENCES users(id),
    content_type text NOT NULL,
    size_bytes   integer NOT NULL CHECK (size_bytes > 0),
    data         bytea NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_attachments_ticket ON support_attachments (ticket_id);

-- Append-only. sender_id is null for SYSTEM messages (joined, status changed...).
CREATE TABLE support_messages (
    id            uuid PRIMARY KEY,
    ticket_id     uuid NOT NULL REFERENCES support_tickets(id),
    sender_id     uuid REFERENCES users(id),
    kind          text NOT NULL CHECK (kind IN ('TEXT','SYSTEM')),
    body          text NOT NULL CHECK (length(body) <= 4000),
    attachment_id uuid REFERENCES support_attachments(id),
    created_at    timestamptz NOT NULL,
    CHECK (kind = 'SYSTEM' OR sender_id IS NOT NULL)
);
CREATE INDEX ix_support_messages_ticket ON support_messages (ticket_id, created_at, id);

INSERT INTO permissions (code, resource, action, description) VALUES
    ('helpdesk:handle', 'helpdesk', 'handle', 'Xem, trả lời mọi yêu cầu hỗ trợ và mời người dùng vào cuộc trò chuyện')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_code, permission_code)
SELECT r, 'helpdesk:handle' FROM unnest(ARRAY['SUPPORT', 'ADMIN']) AS r
WHERE EXISTS (SELECT 1 FROM roles WHERE code = r)
ON CONFLICT DO NOTHING;
