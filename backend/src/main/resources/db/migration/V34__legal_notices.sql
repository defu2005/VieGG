-- Hồ sơ thông báo pháp lý từ nhà phát hành (UC-20, Dev Guide §3 mục 13). Ghi nhận và có hạn phản hồi; không tự động xử lý.
CREATE TABLE legal_notices (
    id                 uuid PRIMARY KEY,
    game_id            uuid REFERENCES games(id),
    publisher_name     text NOT NULL,
    reference_no       text,
    subject            text NOT NULL,
    body               text NOT NULL,
    status             text NOT NULL CHECK (status IN ('OPEN', 'RESPONDED', 'CLOSED')),
    received_at        timestamptz NOT NULL,
    response_deadline  timestamptz NOT NULL,
    recorded_by        uuid NOT NULL REFERENCES users(id),
    created_at         timestamptz NOT NULL
);
CREATE INDEX ix_legal_notices_status_deadline ON legal_notices (status, response_deadline);
