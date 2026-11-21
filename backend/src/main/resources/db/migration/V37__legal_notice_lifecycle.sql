-- Vòng đời hồ sơ UC-20: nhắc sắp hết hạn đúng một lần, đánh dấu quá hạn đúng một lần, ghi thời điểm đổi trạng thái.
ALTER TABLE legal_notices
    ADD COLUMN reminded_at       timestamptz,
    ADD COLUMN overdue_at        timestamptz,
    ADD COLUMN status_changed_at timestamptz;

CREATE INDEX ix_legal_notices_open_deadline ON legal_notices (response_deadline) WHERE status = 'OPEN';
